/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANYDa
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.document.DoubleRange;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FloatRange;
import org.apache.lucene.document.InetAddressPoint;
import org.apache.lucene.document.InetAddressRange;
import org.apache.lucene.document.IntRange;
import org.apache.lucene.document.LongRange;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.BinaryDocValuesRangeQuery;
import org.apache.lucene.queries.BinaryDocValuesRangeQuery.QueryType;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DocValuesFieldExistsQuery;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteArrayDataOutput;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.Explicit;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.geo.ShapeRelation;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.time.DateFormatter;
import org.elasticsearch.common.time.DateMathParser;
import org.elasticsearch.common.util.LocaleUtils;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.mapper.NumberFieldMapper.NumberType;
import org.elasticsearch.index.query.QueryShardContext;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

import static org.elasticsearch.index.query.RangeQueryBuilder.GTE_FIELD;
import static org.elasticsearch.index.query.RangeQueryBuilder.GT_FIELD;
import static org.elasticsearch.index.query.RangeQueryBuilder.LTE_FIELD;
import static org.elasticsearch.index.query.RangeQueryBuilder.LT_FIELD;

/** A {@link FieldMapper} for indexing numeric and date ranges, and creating queries */
public class RangeFieldMapper extends FieldMapper {
    public static final boolean DEFAULT_INCLUDE_UPPER = true;
    public static final boolean DEFAULT_INCLUDE_LOWER = true;

    public static class Defaults {
        public static final Explicit<Boolean> COERCE = new Explicit<>(true, false);
    }

    // this is private since it has a different default
    static final Setting<Boolean> COERCE_SETTING =
        Setting.boolSetting("index.mapping.coerce", true, Setting.Property.IndexScope);

    public static class Builder extends FieldMapper.Builder<Builder, RangeFieldMapper> {
        private Boolean coerce;
        private Locale locale = Locale.ROOT;
        private String pattern;

        public Builder(String name, RangeType type) {
            super(name, new RangeFieldType(type), new RangeFieldType(type));
            builder = this;
        }

        @Override
        public RangeFieldType fieldType() {
            return (RangeFieldType)fieldType;
        }

        @Override
        public Builder docValues(boolean docValues) {
            if (docValues == true) {
                throw new IllegalArgumentException("field [" + name + "] does not currently support " + TypeParsers.DOC_VALUES);
            }
            return super.docValues(docValues);
        }

        public Builder coerce(boolean coerce) {
            this.coerce = coerce;
            return builder;
        }

        protected Explicit<Boolean> coerce(BuilderContext context) {
            if (coerce != null) {
                return new Explicit<>(coerce, true);
            }
            if (context.indexSettings() != null) {
                return new Explicit<>(COERCE_SETTING.get(context.indexSettings()), false);
            }
            return Defaults.COERCE;
        }

        public Builder format(String format) {
            this.pattern = format;
            return this;
        }

        @Override
        public Builder nullValue(Object nullValue) {
            throw new IllegalArgumentException("Field [" + name() + "] does not support null value.");
        }

        public void locale(Locale locale) {
            this.locale = locale;
        }

        @Override
        protected void setupFieldType(BuilderContext context) {
            super.setupFieldType(context);
            DateFormatter formatter = fieldType().dateTimeFormatter;
            if (fieldType().rangeType == RangeType.DATE) {
                boolean hasPatternChanged = Strings.hasLength(builder.pattern) &&
                    Objects.equals(builder.pattern, formatter.pattern()) == false;

                if (hasPatternChanged || Objects.equals(builder.locale, formatter.locale()) == false) {
                    fieldType().setDateTimeFormatter(DateFormatter.forPattern(pattern).withLocale(locale));
                }
            } else if (pattern != null) {
                throw new IllegalArgumentException("field [" + name() + "] of type [" + fieldType().rangeType
                    + "] should not define a dateTimeFormatter unless it is a " + RangeType.DATE + " type");
            }
        }

        @Override
        public RangeFieldMapper build(BuilderContext context) {
            setupFieldType(context);
            return new RangeFieldMapper(name, fieldType, defaultFieldType, coerce(context),
                context.indexSettings(), multiFieldsBuilder.build(this, context), copyTo);
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        final RangeType type;

        public TypeParser(RangeType type) {
            this.type = type;
        }

        @Override
        public Mapper.Builder<?,?> parse(String name, Map<String, Object> node,
                                         ParserContext parserContext) throws MapperParsingException {
            Builder builder = new Builder(name, type);
            TypeParsers.parseField(builder, name, node, parserContext);
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String propName = entry.getKey();
                Object propNode = entry.getValue();
                if (propName.equals("null_value")) {
                    throw new MapperParsingException("Property [null_value] is not supported for [" + this.type.name
                            + "] field types.");
                } else if (propName.equals("coerce")) {
                    builder.coerce(XContentMapValues.nodeBooleanValue(propNode, name + ".coerce"));
                    iterator.remove();
                } else if (propName.equals("locale")) {
                    builder.locale(LocaleUtils.parse(propNode.toString()));
                    iterator.remove();
                } else if (propName.equals("format")) {
                    builder.format(propNode.toString());
                    iterator.remove();
                } else if (TypeParsers.parseMultiField(builder, name, parserContext, propName, propNode)) {
                    iterator.remove();
                }
            }
            return builder;
        }
    }

    public static final class RangeFieldType extends MappedFieldType {
        protected RangeType rangeType;
        protected DateFormatter dateTimeFormatter;
        protected DateMathParser dateMathParser;

        RangeFieldType(RangeType type) {
            super();
            this.rangeType = Objects.requireNonNull(type);
            setTokenized(false);
            setHasDocValues(true);
            setOmitNorms(true);
            if (rangeType == RangeType.DATE) {
                setDateTimeFormatter(DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER);
            }
        }

        RangeFieldType(RangeFieldType other) {
            super(other);
            this.rangeType = other.rangeType;
            if (other.rangeType == RangeType.DATE && other.dateTimeFormatter() != null) {
                setDateTimeFormatter(other.dateTimeFormatter());
            }
        }

        @Override
        public MappedFieldType clone() {
            return new RangeFieldType(this);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) return false;
            RangeFieldType that = (RangeFieldType) o;
            return Objects.equals(rangeType, that.rangeType) &&
            (rangeType == RangeType.DATE) ?
                Objects.equals(dateTimeFormatter, that.dateTimeFormatter)
                : dateTimeFormatter == null && that.dateTimeFormatter == null;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), rangeType, dateTimeFormatter);
        }

        @Override
        public String typeName() {
            return rangeType.name;
        }

        public DateFormatter dateTimeFormatter() {
            return dateTimeFormatter;
        }

        public void setDateTimeFormatter(DateFormatter dateTimeFormatter) {
            checkIfFrozen();
            this.dateTimeFormatter = dateTimeFormatter;
            this.dateMathParser = dateTimeFormatter.toDateMathParser();
        }

        protected DateMathParser dateMathParser() {
            return dateMathParser;
        }

        @Override
        public Query existsQuery(QueryShardContext context) {
            if (hasDocValues()) {
                return new DocValuesFieldExistsQuery(name());
            } else {
                return new TermQuery(new Term(FieldNamesFieldMapper.NAME, name()));
            }
        }

        @Override
        public Query termQuery(Object value, QueryShardContext context) {
            Query query = rangeQuery(value, value, true, true, ShapeRelation.INTERSECTS, null, null, context);
            if (boost() != 1f) {
                query = new BoostQuery(query, boost());
            }
            return query;
        }

        @Override
        public Query rangeQuery(Object lowerTerm, Object upperTerm, boolean includeLower, boolean includeUpper,
                                ShapeRelation relation, ZoneId timeZone, DateMathParser parser, QueryShardContext context) {
            failIfNotIndexed();
            if (parser == null) {
                parser = dateMathParser();
            }
            return rangeType.rangeQuery(name(), hasDocValues(), lowerTerm, upperTerm, includeLower, includeUpper, relation,
                timeZone, parser, context);
        }
    }

    private Explicit<Boolean> coerce;

    private RangeFieldMapper(
        String simpleName,
        MappedFieldType fieldType,
        MappedFieldType defaultFieldType,
        Explicit<Boolean> coerce,
        Settings indexSettings,
        MultiFields multiFields,
        CopyTo copyTo) {
        super(simpleName, fieldType, defaultFieldType, indexSettings, multiFields, copyTo);
        this.coerce = coerce;
    }

    @Override
    public RangeFieldType fieldType() {
        return (RangeFieldType) super.fieldType();
    }

    @Override
    protected String contentType() {
        return fieldType.typeName();
    }

    @Override
    protected RangeFieldMapper clone() {
        return (RangeFieldMapper) super.clone();
    }

    @Override
    protected void parseCreateField(ParseContext context, List<IndexableField> fields) throws IOException {
        Range range;
        if (context.externalValueSet()) {
            range = context.parseExternalValue(Range.class);
        } else {
            XContentParser parser = context.parser();
            final XContentParser.Token start = parser.currentToken();
            if (start == XContentParser.Token.VALUE_NULL) {
                return;
            } else if (start == XContentParser.Token.START_OBJECT) {
                RangeFieldType fieldType = fieldType();
                RangeType rangeType = fieldType.rangeType;
                String fieldName = null;
                Object from = rangeType.minValue();
                Object to = rangeType.maxValue();
                boolean includeFrom = DEFAULT_INCLUDE_LOWER;
                boolean includeTo = DEFAULT_INCLUDE_UPPER;
                XContentParser.Token token;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        fieldName = parser.currentName();
                    } else {
                        if (fieldName.equals(GT_FIELD.getPreferredName())) {
                            includeFrom = false;
                            if (parser.currentToken() != XContentParser.Token.VALUE_NULL) {
                                from = rangeType.parseFrom(fieldType, parser, coerce.value(), includeFrom);
                            }
                        } else if (fieldName.equals(GTE_FIELD.getPreferredName())) {
                            includeFrom = true;
                            if (parser.currentToken() != XContentParser.Token.VALUE_NULL) {
                                from = rangeType.parseFrom(fieldType, parser, coerce.value(), includeFrom);
                            }
                        } else if (fieldName.equals(LT_FIELD.getPreferredName())) {
                            includeTo = false;
                            if (parser.currentToken() != XContentParser.Token.VALUE_NULL) {
                                to = rangeType.parseTo(fieldType, parser, coerce.value(), includeTo);
                            }
                        } else if (fieldName.equals(LTE_FIELD.getPreferredName())) {
                            includeTo = true;
                            if (parser.currentToken() != XContentParser.Token.VALUE_NULL) {
                                to = rangeType.parseTo(fieldType, parser, coerce.value(), includeTo);
                            }
                        } else {
                            throw new MapperParsingException("error parsing field [" +
                                name() + "], with unknown parameter [" + fieldName + "]");
                        }
                    }
                }
                range = new Range(rangeType, from, to, includeFrom, includeTo);
            } else if (fieldType().rangeType == RangeType.IP && start == XContentParser.Token.VALUE_STRING) {
                range = parseIpRangeFromCidr(parser);
            } else {
                throw new MapperParsingException("error parsing field ["
                    + name() + "], expected an object but got " + parser.currentName());
            }
        }
        boolean indexed = fieldType.indexOptions() != IndexOptions.NONE;
        boolean docValued = fieldType.hasDocValues();
        boolean stored = fieldType.stored();
        fields.addAll(fieldType().rangeType.createFields(context, name(), range, indexed, docValued, stored));
        if (docValued == false && (indexed || stored)) {
            createFieldNamesField(context, fields);
        }
    }

    @Override
    protected void doMerge(Mapper mergeWith) {
        super.doMerge(mergeWith);
        RangeFieldMapper other = (RangeFieldMapper) mergeWith;
        if (other.coerce.explicit()) {
            this.coerce = other.coerce;
        }
    }

    @Override
    protected void doXContentBody(XContentBuilder builder, boolean includeDefaults, Params params) throws IOException {
        super.doXContentBody(builder, includeDefaults, params);

        if (fieldType().rangeType == RangeType.DATE
                && (includeDefaults || (fieldType().dateTimeFormatter() != null
                && fieldType().dateTimeFormatter().pattern().equals(DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER.pattern()) == false))) {
            builder.field("format", fieldType().dateTimeFormatter().pattern());
        }
        if (fieldType().rangeType == RangeType.DATE
                && (includeDefaults || (fieldType().dateTimeFormatter() != null
                && fieldType().dateTimeFormatter().locale() != Locale.ROOT))) {
            builder.field("locale", fieldType().dateTimeFormatter().locale());
        }
        if (includeDefaults || coerce.explicit()) {
            builder.field("coerce", coerce.value());
        }
    }

    private static Range parseIpRangeFromCidr(final XContentParser parser) throws IOException {
        final Tuple<InetAddress, Integer> cidr = InetAddresses.parseCidr(parser.text());
        // create the lower value by zeroing out the host portion, upper value by filling it with all ones.
        byte[] lower = cidr.v1().getAddress();
        byte[] upper = lower.clone();
        for (int i = cidr.v2(); i < 8 * lower.length; i++) {
            int m = 1 << 7 - (i & 7);
            lower[i >> 3] &= ~m;
            upper[i >> 3] |= m;
        }
        try {
            return new Range(RangeType.IP, InetAddress.getByAddress(lower), InetAddress.getByAddress(upper), true, true);
        } catch (UnknownHostException bogus) {
            throw new AssertionError(bogus);
        }
    }

    /** Enum defining the type of range */
    public enum RangeType {
        IP("ip_range") {
            @Override
            public Field getRangeField(String name, Range r) {
                return new InetAddressRange(name, (InetAddress)r.from, (InetAddress)r.to);
            }
            @Override
            public InetAddress parseFrom(RangeFieldType fieldType, XContentParser parser, boolean coerce, boolean included)
                    throws IOException {
                InetAddress address = InetAddresses.forString(parser.text());
                return included ? address : nextUp(address);
            }
            @Override
            public InetAddress parseTo(RangeFieldType fieldType, XContentParser parser, boolean coerce, boolean included)
                    throws IOException {
                InetAddress address = InetAddresses.forString(parser.text());
                return included ? address : nextDown(address);
            }
            @Override
            public InetAddress parse(Object value, boolean coerce) {
                if (value instanceof InetAddress) {
                    return (InetAddress) value;
                } else {
                    if (value instanceof BytesRef) {
                        value = ((BytesRef) value).utf8ToString();
                    }
                    return InetAddresses.forString(value.toString());
                }
            }
            @Override
            public InetAddress minValue() {
                return InetAddressPoint.MIN_VALUE;
            }
            @Override
            public InetAddress maxValue() {
                return InetAddressPoint.MAX_VALUE;
            }
            @Override
            public InetAddress nextUp(Object value) {
                return InetAddressPoint.nextUp((InetAddress)value);
            }
            @Override
            public InetAddress nextDown(Object value) {
                return InetAddressPoint.nextDown((InetAddress)value);
            }

            @Override
            public BytesRef encodeRanges(Set<Range> ranges) throws IOException {
                final byte[] encoded = new byte[5 + (16 * 2) * ranges.size()];
                ByteArrayDataOutput out = new ByteArrayDataOutput(encoded);
                out.writeVInt(ranges.size());
                for (Range range : ranges) {
                    InetAddress fromValue = (InetAddress) range.from;
                    byte[] encodedFromValue = InetAddressPoint.encode(fromValue);
                    out.writeBytes(encodedFromValue, 0, encodedFromValue.length);

                    InetAddress toValue = (InetAddress) range.to;
                    byte[] encodedToValue = InetAddressPoint.encode(toValue);
                    out.writeBytes(encodedToValue, 0, encodedToValue.length);
                }
                return new BytesRef(encoded, 0, out.getPosition());
            }

            @Override
            public Query dvRangeQuery(String field, QueryType queryType, Object from, Object to, boolean includeFrom, boolean includeTo) {
                if (includeFrom == false) {
                    from = nextUp(from);
                }

                if (includeTo == false) {
                    to = nextDown(to);
                }

                byte[] encodedFrom = InetAddressPoint.encode((InetAddress) from);
                byte[] encodedTo = InetAddressPoint.encode((InetAddress) to);
                return new BinaryDocValuesRangeQuery(field, queryType, BinaryDocValuesRangeQuery.LengthType.FIXED_16,
                        new BytesRef(encodedFrom), new BytesRef(encodedTo), from, to);
            }

            @Override
            public Query withinQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, from, to, includeFrom, includeTo,
                        (f, t) -> InetAddressRange.newWithinQuery(field, f, t));
            }
            @Override
            public Query containsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, from, to, includeFrom, includeTo,
                        (f, t) -> InetAddressRange.newContainsQuery(field, f, t ));
            }
            @Override
            public Query intersectsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, from, to, includeFrom, includeTo,
                        (f, t) -> InetAddressRange.newIntersectsQuery(field, f ,t ));
            }

            private Query createQuery(String field, Object lower, Object upper, boolean includeLower, boolean includeUpper,
                    BiFunction<InetAddress, InetAddress, Query> querySupplier) {
                byte[] lowerBytes = InetAddressPoint.encode((InetAddress) lower);
                byte[] upperBytes = InetAddressPoint.encode((InetAddress) upper);
                if (Arrays.compareUnsigned(lowerBytes, 0, lowerBytes.length, upperBytes, 0, upperBytes.length) > 0) {
                    throw new IllegalArgumentException(
                            "Range query `from` value (" + lower + ") is greater than `to` value (" + upper + ")");
                }
                InetAddress correctedFrom = includeLower ? (InetAddress) lower : nextUp(lower);
                InetAddress correctedTo = includeUpper ? (InetAddress) upper : nextDown(upper);;
                lowerBytes = InetAddressPoint.encode(correctedFrom);
                upperBytes = InetAddressPoint.encode(correctedTo);
                if (Arrays.compareUnsigned(lowerBytes, 0, lowerBytes.length, upperBytes, 0, upperBytes.length) > 0) {
                    return new MatchNoDocsQuery("float range didn't intersect anything");
                } else {
                    return querySupplier.apply(correctedFrom, correctedTo);
                }
            }
        },
        DATE("date_range", NumberType.LONG) {
            @Override
            public Field getRangeField(String name, Range r) {
                return new LongRange(name, new long[] {((Number)r.from).longValue()}, new long[] {((Number)r.to).longValue()});
            }
            private Number parse(DateMathParser dateMathParser, String dateStr) {
                return dateMathParser.parse(dateStr, () -> {throw new IllegalArgumentException("now is not used at indexing time");})
                    .toEpochMilli();
            }
            @Override
            public Number parseFrom(RangeFieldType fieldType, XContentParser parser, boolean coerce, boolean included)
                    throws IOException {
                Number value = parse(fieldType.dateMathParser, parser.text());
                return included ? value : nextUp(value);
            }
            @Override
            public Number parseTo(RangeFieldType fieldType, XContentParser parser, boolean coerce, boolean included)
                    throws IOException{
                Number value = parse(fieldType.dateMathParser, parser.text());
                return included ? value : nextDown(value);
            }
            @Override
            public Long minValue() {
                return Long.MIN_VALUE;
            }
            @Override
            public Long maxValue() {
                return Long.MAX_VALUE;
            }
            @Override
            public Long nextUp(Object value) {
                return (long) LONG.nextUp(value);
            }
            @Override
            public Long nextDown(Object value) {
                return (long) LONG.nextDown(value);
            }

            @Override
            public BytesRef encodeRanges(Set<Range> ranges) throws IOException {
                return LONG.encodeRanges(ranges);
            }

            @Override
            public Query dvRangeQuery(String field, QueryType queryType, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return LONG.dvRangeQuery(field, queryType, from, to, includeFrom, includeTo);
            }

            @Override
            public Query rangeQuery(String field, boolean hasDocValues, Object lowerTerm, Object upperTerm, boolean includeLower,
                                    boolean includeUpper, ShapeRelation relation, @Nullable ZoneId timeZone,
                                    @Nullable DateMathParser parser, QueryShardContext context) {
                ZoneId zone = (timeZone == null) ? ZoneOffset.UTC : timeZone;

                DateMathParser dateMathParser = (parser == null) ?
                    DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER.toDateMathParser() : parser;
                Long low = lowerTerm == null ? Long.MIN_VALUE :
                    dateMathParser.parse(lowerTerm instanceof BytesRef ? ((BytesRef) lowerTerm).utf8ToString() : lowerTerm.toString(),
                        context::nowInMillis, false, zone).toEpochMilli();
                Long high = upperTerm == null ? Long.MAX_VALUE :
                    dateMathParser.parse(upperTerm instanceof BytesRef ? ((BytesRef) upperTerm).utf8ToString() : upperTerm.toString(),
                        context::nowInMillis, false, zone).toEpochMilli();

                return super.rangeQuery(field, hasDocValues, low, high, includeLower, includeUpper, relation, zone,
                    dateMathParser, context);
            }
            @Override
            public Query withinQuery(String field, Object from, Object to, boolean includeLower, boolean includeUpper) {
                return LONG.withinQuery(field, from, to, includeLower, includeUpper);
            }
            @Override
            public Query containsQuery(String field, Object from, Object to, boolean includeLower, boolean includeUpper) {
                return LONG.containsQuery(field, from, to, includeLower, includeUpper);
            }
            @Override
            public Query intersectsQuery(String field, Object from, Object to, boolean includeLower, boolean includeUpper) {
                return LONG.intersectsQuery(field, from, to, includeLower, includeUpper);
            }
        },
        // todo support half_float
        FLOAT("float_range", NumberType.FLOAT) {
            @Override
            public Float minValue() {
                return Float.NEGATIVE_INFINITY;
            }
            @Override
            public Float maxValue() {
                return Float.POSITIVE_INFINITY;
            }
            @Override
            public Float nextUp(Object value) {
                return Math.nextUp(((Number)value).floatValue());
            }
            @Override
            public Float nextDown(Object value) {
                return Math.nextDown(((Number)value).floatValue());
            }

            @Override
            public BytesRef encodeRanges(Set<Range> ranges) throws IOException {
                return BinaryRangeUtil.encodeFloatRanges(ranges);
            }

            @Override
            public Query dvRangeQuery(String field, QueryType queryType, Object from, Object to, boolean includeFrom, boolean includeTo) {
                if (includeFrom == false) {
                    from = nextUp(from);
                }

                if (includeTo == false) {
                    to = nextDown(to);
                }

                byte[] encodedFrom = BinaryRangeUtil.encodeFloat((Float) from);
                byte[] encodedTo = BinaryRangeUtil.encodeFloat((Float) to);
                return new BinaryDocValuesRangeQuery(field, queryType, BinaryDocValuesRangeQuery.LengthType.FIXED_4,
                        new BytesRef(encodedFrom), new BytesRef(encodedTo), from, to);
            }

            @Override
            public Field getRangeField(String name, Range r) {
                return new FloatRange(name, new float[] {((Number)r.from).floatValue()}, new float[] {((Number)r.to).floatValue()});
            }
            @Override
            public Query withinQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, (Float) from, (Float) to, includeFrom, includeTo,
                        (f, t) -> FloatRange.newWithinQuery(field, new float[] { f }, new float[] { t }), RangeType.FLOAT);
            }
            @Override
            public Query containsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, (Float) from, (Float) to, includeFrom, includeTo,
                        (f, t) -> FloatRange.newContainsQuery(field, new float[] { f }, new float[] { t }), RangeType.FLOAT);
            }
            @Override
            public Query intersectsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, (Float) from, (Float) to, includeFrom, includeTo,
                        (f, t) -> FloatRange.newIntersectsQuery(field, new float[] { f }, new float[] { t }), RangeType.FLOAT);
            }
        },
        DOUBLE("double_range", NumberType.DOUBLE) {
            @Override
            public Double minValue() {
                return Double.NEGATIVE_INFINITY;
            }
            @Override
            public Double maxValue() {
                return Double.POSITIVE_INFINITY;
            }
            @Override
            public Double nextUp(Object value) {
                return Math.nextUp(((Number)value).doubleValue());
            }
            @Override
            public Double nextDown(Object value) {
                return Math.nextDown(((Number)value).doubleValue());
            }

            @Override
            public BytesRef encodeRanges(Set<Range> ranges) throws IOException {
                return BinaryRangeUtil.encodeDoubleRanges(ranges);
            }

            @Override
            public Query dvRangeQuery(String field, QueryType queryType, Object from, Object to, boolean includeFrom, boolean includeTo) {
                if (includeFrom == false) {
                    from = nextUp(from);
                }

                if (includeTo == false) {
                    to = nextDown(to);
                }

                byte[] encodedFrom = BinaryRangeUtil.encodeDouble((Double) from);
                byte[] encodedTo = BinaryRangeUtil.encodeDouble((Double) to);
                return new BinaryDocValuesRangeQuery(field, queryType, BinaryDocValuesRangeQuery.LengthType.FIXED_8,
                        new BytesRef(encodedFrom), new BytesRef(encodedTo), from, to);
            }

            @Override
            public Field getRangeField(String name, Range r) {
                return new DoubleRange(name, new double[] {((Number)r.from).doubleValue()}, new double[] {((Number)r.to).doubleValue()});
            }
            @Override
            public Query withinQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, (Double) from, (Double) to, includeFrom, includeTo,
                        (f, t) -> DoubleRange.newWithinQuery(field, new double[] { f }, new double[] { t }), RangeType.DOUBLE);
            }
            @Override
            public Query containsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, (Double) from, (Double) to, includeFrom, includeTo,
                        (f, t) -> DoubleRange.newContainsQuery(field, new double[] { f }, new double[] { t }), RangeType.DOUBLE);
            }
            @Override
            public Query intersectsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, (Double) from, (Double) to, includeFrom, includeTo,
                        (f, t) -> DoubleRange.newIntersectsQuery(field, new double[] { f }, new double[] { t }), RangeType.DOUBLE);
            }

        },
        // todo add BYTE support
        // todo add SHORT support
        INTEGER("integer_range", NumberType.INTEGER) {
            @Override
            public Integer minValue() {
                return Integer.MIN_VALUE;
            }
            @Override
            public Integer maxValue() {
                return Integer.MAX_VALUE;
            }
            @Override
            public Integer nextUp(Object value) {
                return ((Number)value).intValue() + 1;
            }
            @Override
            public Integer nextDown(Object value) {
                return ((Number)value).intValue() - 1;
            }

            @Override
            public BytesRef encodeRanges(Set<Range> ranges) throws IOException {
                return LONG.encodeRanges(ranges);
            }

            @Override
            public Query dvRangeQuery(String field, QueryType queryType, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return LONG.dvRangeQuery(field, queryType, from, to, includeFrom, includeTo);
            }

            @Override
            public Field getRangeField(String name, Range r) {
                return new IntRange(name, new int[] {((Number)r.from).intValue()}, new int[] {((Number)r.to).intValue()});
            }
            @Override
            public Query withinQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, (Integer) from, (Integer) to, includeFrom, includeTo,
                        (f, t) -> IntRange.newWithinQuery(field, new int[] { f }, new int[] { t }), RangeType.INTEGER);
            }
            @Override
            public Query containsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field,  (Integer) from,  (Integer) to, includeFrom, includeTo,
                        (f, t) -> IntRange.newContainsQuery(field, new int[] { f }, new int[] { t }), RangeType.INTEGER);
            }
            @Override
            public Query intersectsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field,  (Integer) from,  (Integer) to, includeFrom, includeTo,
                        (f, t) -> IntRange.newIntersectsQuery(field, new int[] { f }, new int[] { t }), RangeType.INTEGER);
            }
        },
        LONG("long_range", NumberType.LONG) {
            @Override
            public Long minValue() {
                return Long.MIN_VALUE;
            }
            @Override
            public Long maxValue() {
                return Long.MAX_VALUE;
            }
            @Override
            public Long nextUp(Object value) {
                return ((Number)value).longValue() + 1;
            }
            @Override
            public Long nextDown(Object value) {
                return ((Number)value).longValue() - 1;
            }

            @Override
            public BytesRef encodeRanges(Set<Range> ranges) throws IOException {
                return BinaryRangeUtil.encodeLongRanges(ranges);
            }

            @Override
            public Query dvRangeQuery(String field, QueryType queryType, Object from, Object to, boolean includeFrom, boolean includeTo) {
                if (includeFrom == false) {
                    from = nextUp(from);
                }

                if (includeTo == false) {
                    to = nextDown(to);
                }

                byte[] encodedFrom = BinaryRangeUtil.encodeLong(((Number) from).longValue());
                byte[] encodedTo = BinaryRangeUtil.encodeLong(((Number) to).longValue());
                return new BinaryDocValuesRangeQuery(field, queryType, BinaryDocValuesRangeQuery.LengthType.VARIABLE,
                        new BytesRef(encodedFrom), new BytesRef(encodedTo), from, to);
            }

            @Override
            public Field getRangeField(String name, Range r) {
                return new LongRange(name, new long[] {((Number)r.from).longValue()},
                    new long[] {((Number)r.to).longValue()});
            }
            @Override
            public Query withinQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, (Long) from, (Long) to, includeFrom, includeTo,
                        (f, t) -> LongRange.newWithinQuery(field, new long[] { f }, new long[] { t }), RangeType.LONG);
            }
            @Override
            public Query containsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, (Long) from, (Long) to, includeFrom, includeTo,
                        (f, t) -> LongRange.newContainsQuery(field, new long[] { f }, new long[] { t }), RangeType.LONG);
            }
            @Override
            public Query intersectsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo) {
                return createQuery(field, (Long) from, (Long) to, includeFrom, includeTo,
                        (f, t) -> LongRange.newIntersectsQuery(field, new long[] { f }, new long[] { t }), RangeType.LONG);
            }
        };

        RangeType(String name) {
            this.name = name;
            this.numberType = null;
        }

        RangeType(String name, NumberType type) {
            this.name = name;
            this.numberType = type;
        }

        /** Get the associated type name. */
        public final String typeName() {
            return name;
        }

        /**
         * Internal helper to create the actual {@link Query} using the provided supplier function. Before creating the query we check if
         * the intervals min &gt; max, in which case an {@link IllegalArgumentException} is raised. The method adapts the interval bounds
         * based on whether the edges should be included or excluded. In case where after this correction the interval would be empty
         * because min &gt; max, we simply return a {@link MatchNoDocsQuery}.
         * This helper handles all {@link Number} cases and dates, the IP range type uses its own logic.
         */
        private static <T extends Comparable<T>> Query createQuery(String field, T from, T to, boolean includeFrom, boolean includeTo,
                BiFunction<T, T, Query> querySupplier, RangeType rangeType) {
            if (from.compareTo(to) > 0) {
                // wrong argument order, this is an error the user should fix
                throw new IllegalArgumentException("Range query `from` value (" + from + ") is greater than `to` value (" + to + ")");
            }

            @SuppressWarnings("unchecked")
            T correctedFrom = includeFrom ? from : (T) rangeType.nextUp(from);
            @SuppressWarnings("unchecked")
            T correctedTo =  includeTo ? to : (T) rangeType.nextDown(to);
            if (correctedFrom.compareTo(correctedTo) > 0) {
                return new MatchNoDocsQuery("range didn't intersect anything");
            } else {
                return querySupplier.apply(correctedFrom, correctedTo);
            }
        }

        public abstract Field getRangeField(String name, Range range);
        public List<IndexableField> createFields(ParseContext context, String name, Range range, boolean indexed,
                                                 boolean docValued, boolean stored) {
            assert range != null : "range cannot be null when creating fields";
            List<IndexableField> fields = new ArrayList<>();
            if (indexed) {
                fields.add(getRangeField(name, range));
            }
            if (docValued) {
                BinaryRangesDocValuesField field = (BinaryRangesDocValuesField) context.doc().getByKey(name);
                if (field == null) {
                    field = new BinaryRangesDocValuesField(name, range, this);
                    context.doc().addWithKey(name, field);
                } else {
                    field.add(range);
                }
            }
            if (stored) {
                fields.add(new StoredField(name, range.toString()));
            }
            return fields;
        }
        /** parses from value. rounds according to included flag */
        public Object parseFrom(RangeFieldType fieldType, XContentParser parser, boolean coerce, boolean included) throws IOException {
            Number value = numberType.parse(parser, coerce);
            return included ? value : (Number)nextUp(value);
        }
        /** parses to value. rounds according to included flag */
        public Object parseTo(RangeFieldType fieldType, XContentParser parser, boolean coerce, boolean included) throws IOException {
            Number value = numberType.parse(parser, coerce);
            return included ? value : (Number)nextDown(value);
        }

        public abstract Object minValue();
        public abstract Object maxValue();
        public abstract Object nextUp(Object value);
        public abstract Object nextDown(Object value);
        public abstract Query withinQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo);
        public abstract Query containsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo);
        public abstract Query intersectsQuery(String field, Object from, Object to, boolean includeFrom, boolean includeTo);
        public Object parse(Object value, boolean coerce) {
            return numberType.parse(value, coerce);
        }
        public Query rangeQuery(String field, boolean hasDocValues, Object from, Object to, boolean includeFrom, boolean includeTo,
                                ShapeRelation relation, @Nullable ZoneId timeZone, @Nullable DateMathParser dateMathParser,
                                QueryShardContext context) {
            Object lower = from == null ? minValue() : parse(from, false);
            Object upper = to == null ? maxValue() : parse(to, false);
            Query indexQuery;
            if (relation == ShapeRelation.WITHIN) {
                indexQuery = withinQuery(field, lower, upper, includeFrom, includeTo);
            } else if (relation == ShapeRelation.CONTAINS) {
                indexQuery = containsQuery(field, lower, upper, includeFrom, includeTo);
            } else {
                indexQuery = intersectsQuery(field, lower, upper, includeFrom, includeTo);
            }
            if (hasDocValues) {
                final QueryType queryType;
                if (relation == ShapeRelation.WITHIN) {
                    queryType = QueryType.WITHIN;
                } else if (relation == ShapeRelation.CONTAINS) {
                    queryType = QueryType.CONTAINS;
                } else {
                    queryType = QueryType.INTERSECTS;
                }
                Query dvQuery = dvRangeQuery(field, queryType, lower, upper, includeFrom, includeTo);
                return new IndexOrDocValuesQuery(indexQuery, dvQuery);
            } else {
                return indexQuery;
            }
        }

        // No need to take into account Range#includeFrom or Range#includeTo, because from and to have already been
        // rounded up via parseFrom and parseTo methods.
        public abstract BytesRef encodeRanges(Set<Range> ranges) throws IOException;

        public abstract Query dvRangeQuery(String field, QueryType queryType, Object from, Object to,
                boolean includeFrom, boolean includeTo);

        public final String name;
        private final NumberType numberType;



    }

    /** Class defining a range */
    public static class Range {
        RangeType type;
        Object from;
        Object to;
        private boolean includeFrom;
        private boolean includeTo;

        public Range(RangeType type, Object from, Object to, boolean includeFrom, boolean includeTo) {
            this.type = type;
            this.from = from;
            this.to = to;
            this.includeFrom = includeFrom;
            this.includeTo = includeTo;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(includeFrom ? '[' : '(');
            Object f = includeFrom || from.equals(type.minValue()) ? from : type.nextDown(from);
            Object t = includeTo || to.equals(type.maxValue()) ? to : type.nextUp(to);
            sb.append(type == RangeType.IP ? InetAddresses.toAddrString((InetAddress)f) : f.toString());
            sb.append(" : ");
            sb.append(type == RangeType.IP ? InetAddresses.toAddrString((InetAddress)t) : t.toString());
            sb.append(includeTo ? ']' : ')');
            return sb.toString();
        }
    }

    static class BinaryRangesDocValuesField extends CustomDocValuesField {

        private final Set<Range> ranges;
        private final RangeType rangeType;

        BinaryRangesDocValuesField(String name, Range range, RangeType rangeType) {
            super(name);
            this.rangeType = rangeType;
            ranges = new HashSet<>();
            add(range);
        }

        void add(Range range) {
            ranges.add(range);
        }

        @Override
        public BytesRef binaryValue() {
            try {
                return rangeType.encodeRanges(ranges);
            } catch (IOException e) {
                throw new ElasticsearchException("failed to encode ranges", e);
            }
        }
    }
}
