package com.mcp.scanner;

import java.util.List;

/**
 * 接口参数 Schema 定义。
 * <p>
 * 来源：Comet 平台或 Excel 接口文档。
 * 包含 URL、接口名称、服务端 ID、业务类名、方法名、入参和出参等完整元信息。
 */
public class ApiParamSchema {

    /** 所属微服务 ID（多服务模式下使用，单服务模式为 null） */
    private final String serviceId;
    private final String url;
    private final String apiName;
    private final String serverId;
    private final String className;
    private final String methodName;
    private final String remark;
    private final List<FieldDef> inputs;
    private final List<FieldDef> outputs;

    /**
     * 新构造器：增加 serviceId 参数。
     *
     * @param serviceId  所属微服务 ID（单服务模式传 null）
     * @param url        API 路径
     * @param apiName    接口中文名
     * @param serverId   服务端标识
     * @param className  业务类名
     * @param methodName 方法名
     * @param remark     备注
     * @param inputs     入参列表
     * @param outputs    出参列表
     */
    public ApiParamSchema(String serviceId, String url, String apiName, String serverId,
                          String className, String methodName, String remark,
                          List<FieldDef> inputs, List<FieldDef> outputs) {
        this.serviceId = serviceId;
        this.url = url;
        this.apiName = apiName;
        this.serverId = serverId;
        this.className = className;
        this.methodName = methodName;
        this.remark = remark;
        this.inputs = List.copyOf(inputs != null ? inputs : List.of());
        this.outputs = List.copyOf(outputs != null ? outputs : List.of());
    }

    public String serviceId() { return serviceId; }
    public String url() { return url; }
    public String apiName() { return apiName; }
    public String serverId() { return serverId; }
    public String className() { return className; }
    public String methodName() { return methodName; }
    public String remark() { return remark; }
    public List<FieldDef> inputs() { return inputs; }
    public List<FieldDef> outputs() { return outputs; }

    @Override
    public String toString() {
        return "ApiParamSchema{" +
            "url='" + url + '\'' +
            ", apiName='" + apiName + '\'' +
            ", className='" + className + '\'' +
            ", methodName='" + methodName + '\'' +
            ", inputs=" + inputs.size() +
            ", outputs=" + outputs.size() +
            '}';
    }
}
