package org.benf.cfr.reader.entities;

import org.benf.cfr.reader.bytecode.analysis.parse.literal.TypedLiteral;
import org.benf.cfr.reader.bytecode.analysis.types.ClassNameUtils;
import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.bytecode.analysis.types.RawJavaType;
import org.benf.cfr.reader.entities.attributes.*;
import org.benf.cfr.reader.entities.constantpool.ConstantPool;
import org.benf.cfr.reader.entities.constantpool.ConstantPoolEntryUTF8;
import org.benf.cfr.reader.entities.constantpool.ConstantPoolUtils;
import org.benf.cfr.reader.entityfactories.AttributeFactory;
import org.benf.cfr.reader.entityfactories.ContiguousEntityFactory;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.util.ClassFileVersion;
import org.benf.cfr.reader.util.collections.CollectionUtils;
import org.benf.cfr.reader.util.KnowsRawSize;
import org.benf.cfr.reader.util.TypeUsageCollectable;
import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;


/*
 * Too much in common with method - refactor.
 */

public class Field implements KnowsRawSize, TypeUsageCollectable {
    private static final long OFFSET_OF_ACCESS_FLAGS = 0;
    private static final long OFFSET_OF_NAME_INDEX = 2;
    private static final long OFFSET_OF_DESCRIPTOR_INDEX = 4;
    private static final long OFFSET_OF_ATTRIBUTES_COUNT = 6;
    private static final long OFFSET_OF_ATTRIBUTES = 8;

    private final ConstantPool cp;
    private final long length;
    private final int descriptorIndex;
    private final Set<AccessFlag> accessFlags;
    private final Map<String, Attribute> attributes;
    private final TypedLiteral constantValue;
    private final String fieldName;
    private boolean disambiguate;
    private transient JavaTypeInstance cachedDecodedType;

    public Field(ByteData raw, final ConstantPool cp, final ClassFileVersion classFileVersion) {
        this.cp = cp;
        this.accessFlags = AccessFlag.build(raw.getU2At(OFFSET_OF_ACCESS_FLAGS));
        int attributes_count = raw.getU2At(OFFSET_OF_ATTRIBUTES_COUNT);
        ArrayList<Attribute> tmpAttributes = new ArrayList<Attribute>();
        tmpAttributes.ensureCapacity(attributes_count);
        long attributesLength = ContiguousEntityFactory.build(raw.getOffsetData(OFFSET_OF_ATTRIBUTES), attributes_count, tmpAttributes,
                AttributeFactory.getBuilder(cp, classFileVersion));

        this.attributes = ContiguousEntityFactory.addToMap(new HashMap<String, Attribute>(), tmpAttributes);
        AccessFlag.applyAttributes(attributes, accessFlags);
        this.descriptorIndex = raw.getU2At(OFFSET_OF_DESCRIPTOR_INDEX);
        int nameIndex = raw.getU2At(OFFSET_OF_NAME_INDEX);
        this.length = OFFSET_OF_ATTRIBUTES + attributesLength;
        Attribute cvAttribute = attributes.get(AttributeConstantValue.ATTRIBUTE_NAME);
        this.fieldName = cp.getUTF8Entry(nameIndex).getValue();
        this.disambiguate = false;
        TypedLiteral constValue = null;
        if (cvAttribute != null) {
            constValue = TypedLiteral.getConstantPoolEntry(cp, ((AttributeConstantValue) cvAttribute).getValue());
            if (constValue.getType() == TypedLiteral.LiteralType.Integer) {
                // Need to check if the field is actually something smaller than an integer, and downcast the
                // literal - sufficiently constructed to do this. (although naughty).
                JavaTypeInstance thisType = getJavaTypeInstance();
                if (thisType instanceof RawJavaType) {
                    constValue = TypedLiteral.shrinkTo(constValue, (RawJavaType)thisType);
                }
            }
        }
        this.constantValue = constValue;
    }

    @Override
    public long getRawByteLength() {
        return length;
    }

    private AttributeSignature getSignatureAttribute() {
        Attribute attribute = attributes.get(AttributeSignature.ATTRIBUTE_NAME);
        if (attribute == null) return null;
        return (AttributeSignature) attribute;
    }

    public JavaTypeInstance getJavaTypeInstance() {
        if (cachedDecodedType == null) {
            AttributeSignature sig = getSignatureAttribute();
            ConstantPoolEntryUTF8 signature = sig == null ? null : sig.getSignature();
            ConstantPoolEntryUTF8 descriptor = cp.getUTF8Entry(descriptorIndex);
            ConstantPoolEntryUTF8 prototype;
            if (signature == null) {
                prototype = descriptor;
            } else {
                prototype = signature;
            }
            /*
             * If we've got a signature, use that, otherwise use the descriptor.
             */
            cachedDecodedType = ConstantPoolUtils.decodeTypeTok(prototype.getValue(), cp);
        }
        return cachedDecodedType;
    }

    void setDisambiguate() {
        disambiguate = true;
    }

    public String getFieldName() {
        if (disambiguate) {
            return "var_" + ClassNameUtils.getTypeFixPrefix(getJavaTypeInstance()) + fieldName;
        }
        return fieldName;
    }

    public boolean testAccessFlag(AccessFlag accessFlag) {
        return accessFlags.contains(accessFlag);
    }

    public TypedLiteral getConstantValue() {
        return constantValue;
    }

    private <T extends Attribute> T getAttributeByName(String name) {
        Attribute attribute = attributes.get(name);
        if (attribute == null) return null;
        @SuppressWarnings("unchecked")
        T tmp = (T) attribute;
        return tmp;
    }

    @Override
    public void collectTypeUsages(TypeUsageCollector collector) {
        collector.collect(getJavaTypeInstance());
        collector.collectFrom(getAttributeByName(AttributeRuntimeVisibleAnnotations.ATTRIBUTE_NAME));
        collector.collectFrom(getAttributeByName(AttributeRuntimeInvisibleAnnotations.ATTRIBUTE_NAME));
    }

    public void dump(Dumper d, String name, ClassFile owner) {
        AttributeRuntimeVisibleAnnotations runtimeVisibleAnnotations = getAttributeByName(AttributeRuntimeVisibleAnnotations.ATTRIBUTE_NAME);
        AttributeRuntimeInvisibleAnnotations runtimeInvisibleAnnotations = getAttributeByName(AttributeRuntimeInvisibleAnnotations.ATTRIBUTE_NAME);
        if (runtimeVisibleAnnotations != null) runtimeVisibleAnnotations.dump(d);
        if (runtimeInvisibleAnnotations != null) runtimeInvisibleAnnotations.dump(d);
        String prefix = CollectionUtils.join(accessFlags, " ");
        if (!prefix.isEmpty()) {
            d.keyword(prefix).print(' ');
        }
        JavaTypeInstance type = getJavaTypeInstance();
        d.dump(type).print(' ').fieldName(name, owner.getClassType(), false, false, true);
    }
}
