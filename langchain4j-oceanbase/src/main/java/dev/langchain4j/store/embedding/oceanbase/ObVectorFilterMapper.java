package dev.langchain4j.store.embedding.oceanbase;

import static java.lang.String.format;

import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.*;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;

public class ObVectorFilterMapper {

    private static final String DEFAULT_METADATA_NAME = "metadata";

    private static final String METADATA_JSON_EXTRACT_TEMPLATE = "JSON_EXTRACT(%s, '$.%s')";

    public static String map(Filter filter) {
        if (filter instanceof IsEqualTo) {
            return mapEqual((IsEqualTo) filter);
        } else if (filter instanceof IsNotEqualTo) {
            return mapNotEqual((IsNotEqualTo) filter);
        } else if (filter instanceof IsGreaterThan) {
            return mapGreaterThan((IsGreaterThan) filter);
        } else if (filter instanceof IsGreaterThanOrEqualTo) {
            return mapGreaterThanOrEqual((IsGreaterThanOrEqualTo) filter);
        } else if (filter instanceof IsLessThan) {
            return mapLessThan((IsLessThan) filter);
        } else if (filter instanceof IsLessThanOrEqualTo) {
            return mapLessThanOrEqual((IsLessThanOrEqualTo) filter);
        } else if (filter instanceof IsIn) {
            return mapIn((IsIn) filter);
        } else if (filter instanceof IsNotIn) {
            return mapNotIn((IsNotIn) filter);
        } else if (filter instanceof And) {
            return mapAnd((And) filter);
        } else if (filter instanceof Not) {
            return mapNot((Not) filter);
        } else if (filter instanceof Or) {
            return mapOr((Or) filter);
        } else {
            throw new UnsupportedOperationException(
                    "Unsupported filter type: " + filter.getClass().getName());
        }
    }

    private static String mapEqual(IsEqualTo isEqualTo) {
        String key = formatKey(isEqualTo.key(), isEqualTo.comparisonValue().getClass());
        return format("%s is not null and %s = %s", key, key, formatValue(isEqualTo.comparisonValue()));
    }

    private static String mapNotEqual(IsNotEqualTo isNotEqualTo) {
        String key =
                formatKey(isNotEqualTo.key(), isNotEqualTo.comparisonValue().getClass());
        return format("%s is null or %s != %s", key, key, formatValue(isNotEqualTo.comparisonValue()));
    }

    private static String mapGreaterThan(IsGreaterThan isGreaterThan) {
        return format(
                "%s > %s",
                formatKey(isGreaterThan.key(), isGreaterThan.comparisonValue().getClass()),
                formatValue(isGreaterThan.comparisonValue()));
    }

    private static String mapGreaterThanOrEqual(IsGreaterThanOrEqualTo isGreaterThanOrEqualTo) {
        return format(
                "%s >= %s",
                formatKey(
                        isGreaterThanOrEqualTo.key(),
                        isGreaterThanOrEqualTo.comparisonValue().getClass()),
                formatValue(isGreaterThanOrEqualTo.comparisonValue()));
    }

    private static String mapLessThan(IsLessThan isLessThan) {
        return format(
                "%s < %s",
                formatKey(isLessThan.key(), isLessThan.comparisonValue().getClass()),
                formatValue(isLessThan.comparisonValue()));
    }

    private static String mapLessThanOrEqual(IsLessThanOrEqualTo isLessThanOrEqualTo) {
        return format(
                "%s <= %s",
                formatKey(
                        isLessThanOrEqualTo.key(),
                        isLessThanOrEqualTo.comparisonValue().getClass()),
                formatValue(isLessThanOrEqualTo.comparisonValue()));
    }

    private static String mapIn(IsIn isIn) {
        return format("%s in %s", formatKey(isIn.key(), String.class), formatValuesAsString(isIn.comparisonValues()));
    }

    private static String mapNotIn(IsNotIn isNotIn) {
        String key = formatKey(isNotIn.key(), String.class);
        return format("%s is null or %s not in %s", key, key, formatValuesAsString(isNotIn.comparisonValues()));
    }

    private static String mapAnd(And and) {
        return format("%s and %s", map(and.left()), map(and.right()));
    }

    private static String mapNot(Not not) {
        return format("not(%s)", map(not.expression()));
    }

    private static String mapOr(Or or) {
        return format("(%s or %s)", map(or.left()), map(or.right()));
    }

    private static String formatKey(String key, Class<?> valueType) {
        return String.format(METADATA_JSON_EXTRACT_TEMPLATE, DEFAULT_METADATA_NAME, key);
    }

    private static String formatValue(Object value) {
        if (value instanceof String || value instanceof UUID) {
            return "'" + value + "'";
        } else {
            return value.toString();
        }
    }

    public static String formatValuesAsString(Collection<?> values) {
        return "("
                + values.stream()
                        .map(v -> {
                            if (v instanceof Number) {
                                return String.valueOf(v);
                            } else {
                                return String.format("'%s'", v);
                            }
                        })
                        .collect(Collectors.joining(","))
                + ")";
    }
}
