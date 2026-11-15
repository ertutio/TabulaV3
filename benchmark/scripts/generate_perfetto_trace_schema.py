"""生成 Perfetto TracePacket 完整 schema 表（审计轮 4）。

从固定版本的 perfetto_trace.proto（经 protoc 编译为 descriptor set）提取
全部 message 定义及其字段类型，输出 benchmark/scripts/perfetto_trace_schema.py
（MESSAGES 字典：消息名 → {字段号: 子消息名 或 None}；None = 标量/string/
bytes/map，校验时不递归）。解析由 protoc（grpcio-tools 自带）完成——不手写
proto 语法解析，避免语法边角（单行 message/oneof/enum/map 等）。

固定 schema 版本：perfetto 主分支 commit
40b529923598b739b2892a536a7692eedbed5685（2026-11-07 抓取）。

可复现生成步骤（运行条件：python3 + `pip install grpcio-tools protobuf`，
protoc 由 grpcio-tools 自带，版本随安装记录于本文件头注释）：

    curl -s "https://android.googlesource.com/platform/external/perfetto/+/40b529923598b739b2892a536a7692eedbed5685/protos/perfetto/trace/perfetto_trace.proto?format=TEXT" | base64 -d > perfetto_trace_pinned.proto
    python -m grpc_tools.protoc -I. --descriptor_set_out=trace_pinned.desc perfetto_trace_pinned.proto
    python benchmark/scripts/generate_perfetto_trace_schema.py trace_pinned.desc

更新 schema 版本时更换上述 commit 并重跑三步；重跑为幂等覆盖。
protoc 版本记录：grpcio-tools 安装版本见 pip show grpcio-tools（本轮
1.76.0，protobuf 7.35.1）。
"""

import io
import sys
from google.protobuf import descriptor_pb2

PACKAGE = "perfetto.protos"


def collect_message(msg, messages, prefix=""):
    full = (prefix + "." + msg.name) if prefix else msg.name
    fields = {}
    for f in msg.field:
        if f.type == descriptor_pb2.FieldDescriptorProto.TYPE_MESSAGE or \
           f.type == descriptor_pb2.FieldDescriptorProto.TYPE_GROUP:
            # TYPE_GROUP：本 proto 无 group 字段；出现时按消息处理（防御）
            fields[f.number] = f.type_name.lstrip(".")
        else:
            # 标量/string/bytes：校验时按 wire type 消费，不递归
            fields[f.number] = None
    messages[full] = fields
    for nested in msg.nested_type:
        collect_message(nested, messages, full)
    # enum 跳过（枚举字段按标量）


def collect(file_proto, messages):
    for msg in file_proto.message_type:
        collect_message(msg, messages)


def strip_package(messages):
    out = {}
    for full, fields in messages.items():
        if full.startswith(PACKAGE + "."):
            out[full[len(PACKAGE) + 1:]] = fields
        else:
            out[full] = fields
    return out


def resolve_type_names(messages_with_raw):
    """字段类型名按消息作用域向外解析为注册表键（去包前缀后的完整名）。"""
    registry = messages_with_raw
    schema = {}
    for full, fields in registry.items():
        resolved = {}
        for num, ref in fields.items():
            if ref is None:
                resolved[num] = None
                continue
            ref = ref.lstrip(".")
            if ref.startswith(PACKAGE + "."):
                ref = ref[len(PACKAGE) + 1:]
            parts = full.split(".")
            candidate = None
            for k in range(len(parts), 0, -1):
                trial = ".".join(parts[:k]) + "." + ref
                if trial in registry:
                    candidate = trial
                    break
            if candidate is None and ref in registry:
                candidate = ref
            resolved[num] = candidate  # 未知引用按未知消息处理（仅框架走查）
        schema[full] = resolved
    return schema


def main(descriptor_path: str, out_path: str):
    fds = descriptor_pb2.FileDescriptorSet()
    fds.ParseFromString(open(descriptor_path, "rb").read())
    assert len(fds.file) >= 1, "empty descriptor set"
    messages = {}
    for file_proto in fds.file:
        collect(file_proto, messages)
    schema = resolve_type_names(messages)
    assert "TracePacket" in schema, "TracePacket missing"
    assert schema["TracePacket"].get(11) == "TrackEvent", \
        "TracePacket.track_event(11) -> TrackEvent must resolve"
    assert schema.get("TrackEvent", {}).get(4) == "DebugAnnotation", \
        "TrackEvent.debug_annotation(4) -> DebugAnnotation must resolve"

    lines = [
        '"""Perfetto TracePacket 完整 schema 表（自动生成，勿手改）。',
        "",
        "来源版本：perfetto 主分支 commit",
        "40b529923598b739b2892a536a7692eedbed5685（2026-11-07 抓取）；",
        "由 protoc（grpcio-tools 1.76.0，protobuf 7.35.1）编译为 descriptor set",
        "后经 generate_perfetto_trace_schema.py 提取——生成步骤见其模块文档。",
        "",
        "结构：MESSAGES[消息名] = {字段号: 子消息名 或 None}；",
        "None = 标量/string/bytes/map（校验时按 wire type 消费、不递归；map 的",
        "合成 MapEntry 作为消息递归其 key/value 框架）；消息型字段（wire type=2",
        "且命中子消息名）按子消息 schema 递归实际解析——嵌套损坏可识别。",
        '"""',
        "",
        "MESSAGES = {",
    ]
    for full in sorted(schema):
        fields = schema[full]
        entries = ", ".join(f"{num}: {repr(sub)}" for num, sub in sorted(fields.items()))
        lines.append(f"    {full!r}: {{{entries}}},")
    lines.append("}")
    lines.append("")
    io.open(out_path, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
    print(f"messages={len(schema)} -> {out_path}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else
         "benchmark/scripts/perfetto_trace_schema.py")
