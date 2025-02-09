package org.benf.cfr.reader.entities;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.variables.Ident;
import org.benf.cfr.reader.bytecode.analysis.variables.VariableNamer;
import org.benf.cfr.reader.bytecode.analysis.variables.VariableNamerFactory;
import org.benf.cfr.reader.bytecode.analysis.types.*;
import org.benf.cfr.reader.entities.attributes.*;
import org.benf.cfr.reader.entities.constantpool.ConstantPool;
import org.benf.cfr.reader.entities.constantpool.ConstantPoolEntryClass;
import org.benf.cfr.reader.entities.constantpool.ConstantPoolEntryUTF8;
import org.benf.cfr.reader.entities.constantpool.ConstantPoolUtils;
import org.benf.cfr.reader.entityfactories.AttributeFactory;
import org.benf.cfr.reader.entityfactories.ContiguousEntityFactory;
import org.benf.cfr.reader.state.DCCommonState;
import org.benf.cfr.reader.state.LocalClassAwareTypeUsageInformation;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.state.TypeUsageInformation;
import org.benf.cfr.reader.util.*;
import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.collections.CollectionUtils;
import org.benf.cfr.reader.util.collections.MapFactory;
import org.benf.cfr.reader.util.collections.SetFactory;
import org.benf.cfr.reader.util.functors.UnaryFunction;
import org.benf.cfr.reader.util.getopt.Options;
import org.benf.cfr.reader.util.getopt.OptionsImpl;
import org.benf.cfr.reader.util.output.Dumper;
import org.benf.cfr.reader.util.output.TypeOverridingDumper;

import java.util.*;

/* Too much in common with field - refactor.
 *
 */

public class Method implements KnowsRawSize, TypeUsageCollectable {

    public enum MethodConstructor {
        NOT(false, false),
        STATIC_CONSTRUCTOR(false, false),
        CONSTRUCTOR(true, false),
        ENUM_CONSTRUCTOR(true, true),
        // Eclipse enums behave like normal enums, except they declare arguments.
        ECLIPSE_ENUM_CONSTRUCTOR(true, true);

        private final boolean isConstructor;
        private final boolean isEnumConstructor;

        MethodConstructor(boolean isConstructor, boolean isEnumConstructor) {
            this.isConstructor = isConstructor;
            this.isEnumConstructor = isEnumConstructor;
        }

        public boolean isConstructor() {
            return isConstructor;
        }

        public boolean isEnumConstructor() {
            return isEnumConstructor;
        }
    }

    public enum Visibility
    {
        Visible,
        HiddenSynthetic,
        HiddenBridge
    }

    private static final long OFFSET_OF_ACCESS_FLAGS = 0;
    private static final long OFFSET_OF_NAME_INDEX = 2;
    private static final long OFFSET_OF_DESCRIPTOR_INDEX = 4;
    private static final long OFFSET_OF_ATTRIBUTES_COUNT = 6;
    private static final long OFFSET_OF_ATTRIBUTES = 8;

    private final long length;
    private final EnumSet<AccessFlagMethod> accessFlags;
    private final Map<String, Attribute> attributes;
    private final MethodConstructor isConstructor;
    private final int descriptorIndex;
    private final AttributeCode codeAttribute;
    private final ConstantPool cp;
    private final VariableNamer variableNamer;
    private final MethodPrototype methodPrototype;
    private final ClassFile classFile;
    private Visibility hidden;
    private DecompilerComments comments;
    private final Map<JavaRefTypeInstance, String> localClasses = MapFactory.newOrderedMap();
    private boolean isOverride;
    private transient Set<JavaTypeInstance> thrownTypes = null;

    public Method(ByteData raw, ClassFile classFile, final ConstantPool cp, final DCCommonState dcCommonState, final ClassFileVersion classFileVersion) {
        Options options = dcCommonState.getOptions();

        this.cp = cp;
        this.classFile = classFile;
        this.accessFlags = AccessFlagMethod.build(raw.getU2At(OFFSET_OF_ACCESS_FLAGS));
        this.descriptorIndex = raw.getU2At(OFFSET_OF_DESCRIPTOR_INDEX);
        this.hidden = Visibility.Visible;
        int nameIndex = raw.getU2At(OFFSET_OF_NAME_INDEX);
        String initialName = cp.getUTF8Entry(nameIndex).getValue();

        int numAttributes = raw.getU2At(OFFSET_OF_ATTRIBUTES_COUNT);
        ArrayList<Attribute> tmpAttributes = new ArrayList<Attribute>();
        tmpAttributes.ensureCapacity(numAttributes);
        long attributesLength = ContiguousEntityFactory.build(raw.getOffsetData(OFFSET_OF_ATTRIBUTES), numAttributes, tmpAttributes,
                AttributeFactory.getBuilder(cp, classFileVersion));

        this.attributes = ContiguousEntityFactory.addToMap(new HashMap<String, Attribute>(), tmpAttributes);
        AccessFlagMethod.applyAttributes(attributes, accessFlags);
        this.length = OFFSET_OF_ATTRIBUTES + attributesLength;

        MethodConstructor methodConstructor = MethodConstructor.NOT;
        if (initialName.equals(MiscConstants.INIT_METHOD)) {
            boolean isEnum = classFile.getAccessFlags().contains(AccessFlag.ACC_ENUM);
            methodConstructor = isEnum ? MethodConstructor.ENUM_CONSTRUCTOR : MethodConstructor.CONSTRUCTOR;
        } else if (initialName.equals(MiscConstants.STATIC_INIT_METHOD)) {
            methodConstructor = MethodConstructor.STATIC_CONSTRUCTOR;
        }
        this.isConstructor = methodConstructor;
        if (methodConstructor.isConstructor() && accessFlags.contains(AccessFlagMethod.ACC_STRICT)) {
            accessFlags.remove(AccessFlagMethod.ACC_STRICT);
            classFile.getAccessFlags().add(AccessFlag.ACC_STRICT);
        }

        Attribute codeAttribute = attributes.get(AttributeCode.ATTRIBUTE_NAME);
        if (codeAttribute == null) {
            // Because we don't have a code attribute, we don't have a local variable table.
            this.variableNamer = VariableNamerFactory.getNamer(null, cp);
            this.codeAttribute = null;
        } else {
            this.codeAttribute = (AttributeCode) codeAttribute;
            AttributeLocalVariableTable variableTable = options.getOption(OptionsImpl.USE_NAME_TABLE) ? this.codeAttribute.getLocalVariableTable() : null;
            this.variableNamer = VariableNamerFactory.getNamer(variableTable, cp);
            // This rigamarole is neccessary because we don't provide the factory for the code attribute enough information
            // to get the Method (this).
            this.codeAttribute.setMethod(this);
        }
        this.methodPrototype = generateMethodPrototype(initialName, methodConstructor);
        if (accessFlags.contains(AccessFlagMethod.ACC_BRIDGE) &&
                // javac only ever generates bridges into instances,
                // however kotlin loses useful info if we hide them.
                !accessFlags.contains(AccessFlagMethod.ACC_STATIC) &&
                options.getOption(OptionsImpl.HIDE_BRIDGE_METHODS)) {
            this.hidden = Visibility.HiddenBridge;
        }
    }

    void releaseCode() {
        if (codeAttribute != null) {
            codeAttribute.releaseCode();
        }
        attributes.clear();
    }

    @Override
    public void collectTypeUsages(TypeUsageCollector collector) {
        methodPrototype.collectTypeUsages(collector);
        collector.collectFrom(getAttributeByName(AttributeRuntimeVisibleAnnotations.ATTRIBUTE_NAME));
        collector.collectFrom(getAttributeByName(AttributeRuntimeInvisibleAnnotations.ATTRIBUTE_NAME));
        collector.collectFrom(getAttributeByName(AttributeRuntimeVisibleParameterAnnotations.ATTRIBUTE_NAME));
        collector.collectFrom(getAttributeByName(AttributeRuntimeInvisibleParameterAnnotations.ATTRIBUTE_NAME));
        collector.collectFrom(getAttributeByName(AttributeAnnotationDefault.ATTRIBUTE_NAME));
        if (codeAttribute != null) {
            codeAttribute.collectTypeUsages(collector);
            codeAttribute.analyse().collectTypeUsages(collector);
        }
        collector.collect(localClasses.keySet());
        collector.collectFrom(getAttributeByName(AttributeExceptions.ATTRIBUTE_NAME));
    }

    public boolean copyLocalClassesFrom(Method other) {
        for (Map.Entry<JavaRefTypeInstance, String> entry : other.localClasses.entrySet()) {
            markUsedLocalClassType(entry.getKey(), entry.getValue());
        }
        return !other.localClasses.isEmpty();
    }

    public Set<AccessFlagMethod> getAccessFlags() {
        return accessFlags;
    }

    public void hideSynthetic() {
        this.hidden = Visibility.HiddenSynthetic;
    }

    public Visibility hiddenState() {
        return hidden;
    }

    public boolean testAccessFlag(AccessFlagMethod flag) {
        return accessFlags.contains(flag);
    }

    public MethodConstructor getConstructorFlag() {
        return isConstructor;
    }

    AttributeSignature getSignatureAttribute() {
        return this.getAttributeByName(AttributeSignature.ATTRIBUTE_NAME);
    }

    private <T extends Attribute> T getAttributeByName(String name) {
        Attribute attribute = attributes.get(name);
        if (attribute == null) return null;
        @SuppressWarnings("unchecked")
        T tmp = (T) attribute;
        return tmp;
    }

    public VariableNamer getVariableNamer() {
        return variableNamer;
    }

    public ClassFile getClassFile() {
        return classFile;
    }

    @Override
    public long getRawByteLength() {
        return length;
    }

    public String getName() {
        return methodPrototype.getName();
    }

    /* This is a bit ugly - otherwise though we need to tie a variable namer to this earlier.
     * We can't always use the signature... in an enum, for example, it lies!
     *
     * Method  : <init> name : 30, descriptor 31
     * Descriptor ConstantUTF8[(Ljava/lang/String;I)V]
     * Signature Signature:ConstantUTF8[()V]
     */
    private MethodPrototype generateMethodPrototype(String initialName, MethodConstructor constructorFlag) {
        AttributeSignature sig = getSignatureAttribute();
        ConstantPoolEntryUTF8 signature = sig == null ? null : sig.getSignature();
        ConstantPoolEntryUTF8 descriptor = cp.getUTF8Entry(descriptorIndex);
        ConstantPoolEntryUTF8 prototype;
        if (signature == null) {
            // This is 'fun'.  Eclipse doesn't provide a signature, and its descriptor is honest.
            // java does, which means that javac's signature disagrees with the descriptor.
            if (constructorFlag == MethodConstructor.ENUM_CONSTRUCTOR) {
                constructorFlag = MethodConstructor.ECLIPSE_ENUM_CONSTRUCTOR;
            }
            prototype = descriptor;
        } else {
            prototype = signature;
        }
        boolean isInstance = !accessFlags.contains(AccessFlagMethod.ACC_STATIC);
        boolean isVarargs = accessFlags.contains(AccessFlagMethod.ACC_VARARGS);
        boolean isSynthetic = accessFlags.contains(AccessFlagMethod.ACC_SYNTHETIC);
        DCCommonState state = cp.getDCCommonState();
        MethodPrototype res = ConstantPoolUtils.parseJavaMethodPrototype(state, classFile, classFile.getClassType(), initialName, isInstance, constructorFlag, prototype, cp, isVarargs, isSynthetic, variableNamer);

        /*
         * Work around bug in inner class signatures.
         *
         * http://stackoverflow.com/questions/15131040/java-inner-class-inconsistency-between-descriptor-and-signature-attribute-clas
         */
        if (classFile.isInnerClass()) {
            if (signature != null) {
                MethodPrototype descriptorProto = ConstantPoolUtils.parseJavaMethodPrototype(state, classFile, classFile.getClassType(), initialName, isInstance, constructorFlag, descriptor, cp, isVarargs, isSynthetic, variableNamer);
                if (descriptorProto.getArgs().size() != res.getArgs().size()) {
                    // error due to inner class sig bug.
                    fixupInnerClassSignature(descriptorProto, res);
                }
            }
        }
        return res;
    }

    private static void fixupInnerClassSignature(MethodPrototype descriptor, MethodPrototype signature) {
        List<JavaTypeInstance> descriptorArgs = descriptor.getArgs();
        List<JavaTypeInstance> signatureArgs = signature.getArgs();
        if (signatureArgs.size() != descriptorArgs.size() - 1) {
            // It's not the known issue, can't really deal with it.
            signature.setDescriptorProto(descriptor);
            return;
        }
        for (int x = 0; x < signatureArgs.size(); ++x) {
            if (!descriptorArgs.get(x + 1).equals(signatureArgs.get(x).getDeGenerifiedType())) {
                // Incompatible.
                return;
            }
        }
        // Ok.  We've fallen foul of the bad signature-on-inner-class
        // compiler bug.  Patch up the inner class signature so that it takes the implicit
        // outer this pointer.
        // Since we've got the ref to the mutable signatureArgs, let's be DISGUSTING and mutate that.
        signatureArgs.add(0, descriptorArgs.get(0));
    }

    public MethodPrototype getMethodPrototype() {
        return methodPrototype;
    }

    void markOverride() {
        isOverride = true;
    }

    public void markUsedLocalClassType(JavaTypeInstance javaTypeInstance, String suggestedName) {
        javaTypeInstance = javaTypeInstance.getDeGenerifiedType();
        if (!(javaTypeInstance instanceof JavaRefTypeInstance))
            throw new IllegalStateException("Bad local class Type " + javaTypeInstance.getRawName());
        localClasses.put((JavaRefTypeInstance) javaTypeInstance, suggestedName);
    }

    public void markUsedLocalClassType(JavaTypeInstance javaTypeInstance) {
        markUsedLocalClassType(javaTypeInstance, null);
    }

    private void dumpMethodAnnotations(Dumper d) {
        AttributeRuntimeVisibleAnnotations runtimeVisibleAnnotations = getAttributeByName(AttributeRuntimeVisibleAnnotations.ATTRIBUTE_NAME);
        AttributeRuntimeInvisibleAnnotations runtimeInvisibleAnnotations = getAttributeByName(AttributeRuntimeInvisibleAnnotations.ATTRIBUTE_NAME);
        if (runtimeVisibleAnnotations != null) runtimeVisibleAnnotations.dump(d);
        if (runtimeInvisibleAnnotations != null) runtimeInvisibleAnnotations.dump(d);
        if (isOverride) {
            d.print("@Override").newln();
        }
    }

    public Set<JavaTypeInstance> getThrownTypes() {
        if (thrownTypes == null) {
            thrownTypes = SetFactory.newOrderedSet();
            AttributeExceptions exceptionsAttribute = getAttributeByName(AttributeExceptions.ATTRIBUTE_NAME);
            if (exceptionsAttribute != null) {
                List<ConstantPoolEntryClass> exceptionClasses = exceptionsAttribute.getExceptionClassList();
                for (ConstantPoolEntryClass exceptionClass : exceptionClasses) {
                    JavaTypeInstance typeInstance = exceptionClass.getTypeInstance();
                    thrownTypes.add(typeInstance);
                }
            }
        }
        return thrownTypes;
    }

    private void dumpSignatureText(boolean asClass, Dumper d) {

        dumpMethodAnnotations(d);

        EnumSet<AccessFlagMethod> localAccessFlags = accessFlags;
        if (!asClass) {
            if (codeAttribute != null && !accessFlags.contains(AccessFlagMethod.ACC_STATIC)
                    && !accessFlags.contains(AccessFlagMethod.ACC_PRIVATE)) {
                d.keyword("default ");
            }
            // Dumping as interface.
            localAccessFlags = SetFactory.newSet(localAccessFlags);
            localAccessFlags.remove(AccessFlagMethod.ACC_ABSTRACT);
        }
        localAccessFlags.remove(AccessFlagMethod.ACC_VARARGS);
        String prefix = CollectionUtils.join(localAccessFlags, " ");

        if (!prefix.isEmpty()) d.keyword(prefix);

        if (isConstructor == MethodConstructor.STATIC_CONSTRUCTOR) {
            return;
        }

        if (!prefix.isEmpty()) d.print(' ');

        MethodPrototypeAnnotationsHelper paramAnnotationsHelper = new MethodPrototypeAnnotationsHelper(
                this.<AttributeRuntimeVisibleParameterAnnotations>getAttributeByName(AttributeRuntimeVisibleParameterAnnotations.ATTRIBUTE_NAME),
                this.<AttributeRuntimeInvisibleParameterAnnotations>getAttributeByName(AttributeRuntimeInvisibleParameterAnnotations.ATTRIBUTE_NAME)
        );

        String displayName = isConstructor.isConstructor() ?
                d.getTypeUsageInformation().getName(classFile.getClassType()) :
                methodPrototype.getFixedName();

        getMethodPrototype().dumpDeclarationSignature(d, displayName, isConstructor, paramAnnotationsHelper);
        AttributeExceptions exceptionsAttribute = getAttributeByName(AttributeExceptions.ATTRIBUTE_NAME);
        if (exceptionsAttribute != null) {
            d.print(" throws ");
            boolean first = true;
            for (JavaTypeInstance typeInstance : getThrownTypes()) {
                first = StringUtils.comma(first, d);
                d.dump(typeInstance);
            }
        }
    }

    public Op04StructuredStatement getAnalysis() {
        if (codeAttribute == null) throw new ConfusedCFRException("No code in this method to analyze");
        Op04StructuredStatement analysis = codeAttribute.analyse();
        return analysis;
    }

    public boolean isConstructor() {
        return isConstructor.isConstructor();
    }

    void analyse() {
        try {
            if (codeAttribute != null) {
                codeAttribute.analyse();
            }
            if (!methodPrototype.parametersComputed()) {
                /*
                 * No code attribute - we still need to assign variable names.
                 */
                Map<Integer, Ident> identMap = MapFactory.newLazyMap(new UnaryFunction<Integer, Ident>() {
                    @Override
                    public Ident invoke(Integer arg) {
                        return new Ident(arg, 0);
                    }
                });
                methodPrototype.computeParameters(getConstructorFlag(), identMap);
            }
        } catch (RuntimeException e) {
            System.out.println("While processing method : " + this.getName());
            throw e;
        }
    }

    public boolean hasCodeAttribute() {
        return codeAttribute != null;
    }

    public AttributeCode getCodeAttribute() {
        return codeAttribute;
    }

    private void dumpComments(Dumper d) {
        if (comments != null) {
            comments.dump(d);

            for (DecompilerComment decompilerComment : comments.getCommentCollection()) {
                String string = decompilerComment.getSummaryMessage();
                if (string != null) {
                    d.addSummaryError(this, string);
                }
            }
        }
    }

    public void setComments(DecompilerComments comments) {
        this.comments = comments;
    }

    private boolean isInnerVisibleTo(JavaTypeInstance maybeCaller) {
        JavaRefTypeInstance thisClass = getClassFile().getRefClassType();
            /*
             * If this is an inner class of maybeCaller (or the other way around), it's allowed, otherwise
             * not.
             */
        if (maybeCaller.getInnerClassHereInfo().isTransitiveInnerClassOf(thisClass)) return true;
        if (thisClass.getInnerClassHereInfo().isTransitiveInnerClassOf(maybeCaller)) return true;
        return false;
    }

    public boolean isVisibleTo(JavaRefTypeInstance maybeCaller) {
        if (accessFlags.contains(AccessFlagMethod.ACC_PUBLIC)) return true;
        if (maybeCaller.equals(getClassFile().getClassType())) return true;
        if (accessFlags.contains(AccessFlagMethod.ACC_PRIVATE)) {
            return isInnerVisibleTo(maybeCaller);
        }
        if (accessFlags.contains(AccessFlagMethod.ACC_PROTECTED)) {
            // If it's derived, it can see it - if it's inner, it can see it.
            BindingSuperContainer bindingSuperContainer = maybeCaller.getBindingSupers();
            // paranoia.
            if (bindingSuperContainer == null) return false;
            if (bindingSuperContainer.containsBase(getClassFile().getClassType())) return true;
            return isInnerVisibleTo(maybeCaller);
        }
        // Otherwise, we're left with package visibility.
        if (maybeCaller.getPackageName().equals(getClassFile().getRefClassType().getPackageName())) return true;
        return false;
    }

    public void dump(Dumper d, boolean asClass) {
        if (codeAttribute != null) {
            // force analysis so we have comments.
            codeAttribute.analyse();
        }
        dumpComments(d);
        dumpSignatureText(asClass, d);
        if (codeAttribute == null) {
            AttributeAnnotationDefault annotationDefault = getAttributeByName(AttributeAnnotationDefault.ATTRIBUTE_NAME);
            if (annotationDefault != null) {
                JavaTypeInstance resultType = methodPrototype.getReturnType();
                d.keyword(" default ").dump(annotationDefault.getElementValue().withTypeHint(resultType));
            }
            d.endCodeln();
        } else {
            /*
             * Override the dumper with a proxy which makes sure that local classes defined here are 'better'.
             */
            if (!localClasses.isEmpty()) {
                TypeUsageInformation overrides = new LocalClassAwareTypeUsageInformation(localClasses, d.getTypeUsageInformation());
                d = new TypeOverridingDumper(d, overrides);
            }
            d.print(' ').dump(codeAttribute);
        }
    }

    @Override
    public String toString() {
        return getName() + ": " + methodPrototype;
    }
}
