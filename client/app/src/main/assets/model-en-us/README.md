# Vosk 英文离线语音模型

把模型文件解压到**本目录**（`client/app/src/main/assets/model-en-us/`）。

推荐：`vosk-model-small-en-us-0.15`  
官方列表：https://alphacephei.com/vosk/models

解压后目录内应直接有：

```
am/
conf/
graph/
ivector/
uuid          ← 必填：Vosk StorageService 用它检测模型版本
```

若缺 `uuid`，在本目录执行：

```bash
uuidgen | tr '[:upper:]' '[:lower:]' > client/app/src/main/assets/model-en-us/uuid
```

或重新跑安装脚本（已存在模型时也会自动补 uuid）。

也可把 zip 放到任意位置后执行（支持自定义地址）：

```bash
VOSK_MODEL_URL='你的zip地址或本地file://路径' ./scripts/fetch_vosk_model.sh
# 或本地 zip：
./scripts/fetch_vosk_model.sh /path/to/vosk-model-small-en-us-0.15.zip
```
