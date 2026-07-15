package com.mcp.scanner;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字段定义。
 * <p>
 * 描述 API 接口的一个入参或出参字段，包含类型、说明、长度、校验规则等元信息。
 * 支持嵌套子字段（当 type 为 List 时）。
 */
public final class FieldDef {

    /** 字段名称（如 branch、ccy、rateArray） */
    private final String name;

    /** 字段类型（如 String(50)、BigDecimal、Integer(5)、List） */
    private final String type;

    /** 中文字段说明 */
    private final String description;

    /** 是否必输 */
    private final boolean required;

    /** 取值范围（如 D,I） */
    private final String validValues;

    /** 约束条件 */
    private final String constraints;

    /** 正则表达式 */
    private final String regex;

    /** 最大长度 */
    private final Integer maxLength;

    /** 最小长度 */
    private final Integer minLength;

    /** 备注 */
    private final String remark;

    /** 嵌套子字段（当 type 为 List 时包含子项定义） */
    private final List<FieldDef> children;

    public FieldDef(String name, String type, String description, boolean required,
                    String validValues, String constraints, String regex,
                    Integer maxLength, Integer minLength, String remark,
                    List<FieldDef> children) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.required = required;
        this.validValues = validValues;
        this.constraints = constraints;
        this.regex = regex;
        this.maxLength = maxLength;
        this.minLength = minLength;
        this.remark = remark;
        this.children = children != null ? List.copyOf(children) : null;
    }

    public String name() { return name; }
    public String type() { return type; }
    public String description() { return description; }
    public boolean required() { return required; }
    public String validValues() { return validValues; }
    public String constraints() { return constraints; }
    public String regex() { return regex; }
    public Integer maxLength() { return maxLength; }
    public Integer minLength() { return minLength; }
    public String remark() { return remark; }
    public List<FieldDef> children() { return children; }

    /**
     * 获取纯净的 JSON Schema 类型。
     * String(50) → string, BigDecimal → number, Integer → integer, List → array
     */
    public String schemaType() {
        if (type == null) return "string";
        String base = type.replaceAll("\\(.*\\)", "").trim();
        if ("String".equals(base)) return "string";
        if ("Integer".equals(base)) return "integer";
        if ("BigDecimal".equals(base)) return "number";
        if ("List".equals(base)) return "array";
        if ("Date".equals(base)) return "string";
        return "string";
    }

    /**
     * 提取类型括号中的长度信息，如 String(50) → 50
     */
    public Integer extractLength() {
        if (type == null) return null;
        Matcher m = Pattern.compile("\\((\\d+)\\)").matcher(type);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FieldDef)) return false;
        FieldDef fieldDef = (FieldDef) o;
        return required == fieldDef.required
            && Objects.equals(name, fieldDef.name)
            && Objects.equals(type, fieldDef.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, required);
    }

    @Override
    public String toString() {
        return "FieldDef{name='" + name + "', type='" + type + "', required=" + required + "}";
    }
}
