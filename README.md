# Moci (墨词)

A mobile-first English vocabulary app for primary-school learners. Students and parents use the **Android app**. The **web UI is admin-only**.

The UI is in Chinese. This document is in English.

## Roles

| Role | Where | What they do |
| --- | --- | --- |
| **Student** | Android app | Study new words and review words already at the **familiar** stage. |
| **Parent** | Android app | View bound children’s progress, set daily **new-word** and **review** quotas, and switch into a child’s account without a password. |
| **Admin** | Web | Approve or reject sign-ups, maintain the shared word bank, bind children to parents, and view learning reports. Admins do not study words. |

The first web registration becomes admin automatically. Later student and parent accounts are created in the app and stay **pending** until an admin approves them.

## Study rules

- Students study in the app: **Home**, **Study**, and **Me**.
- Each day a student has two tasks: **new words** and **review of familiar (了解) words**.
- Parents set both daily amounts in the app (0–50 each, default 8).
- New word + **I know it** + correct spelling → **familiar (了解)**.
- Familiar word + **I know it** + correct spelling → **mastered (掌握)**.
- **I don’t know it** → the word returns to the new-word pool.
- Learning reports split activity into **new-word study** vs **familiar-word review**.

## Account switching

In the app on **Me**:

- **Parent → child:** one tap, no password.
- **Child → parent:** the child must enter that parent’s login password.
- Switching is limited to accounts already bound by an admin.

## Stack

- Python 3 + Flask
- SQLite (default `server/instance/words.db`)
- Gunicorn for production
- Mobile-first HTML/CSS (no frontend framework)
- Android (Kotlin + Jetpack Compose)

## Run locally

### Server

```bash
cd server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python3 app.py
```

Then open `http://127.0.0.1:5000`. The first visit creates tables if needed and a secret key under `server/instance/`.

Android 客户端安装包下载：

- 直接下载：`http://127.0.0.1:5000/download/moci.apk`
- 版本信息：`GET /api/v1/app/info`（返回 `download_url`、版本号、文件大小）

重新编译并发布 APK：

```bash
./scripts/build_release_apk.sh
```

上传到服务器（需已配置 SSH，默认 `root@cn`）：

```bash
MOCI_UPLOAD=1 ./scripts/build_release_apk.sh
```

The web login is for admins. Register the first account on the web (it becomes admin). Students and parents register in the Android app. Approve later users from **Users**.

### Client

朗读检查使用 **Vosk 本地识别**。请先自行下载英文小模型并放到 assets：

1. 下载 [vosk-model-small-en-us-0.15](https://alphacephei.com/vosk/models)（约 40MB zip）
2. 解压内容放到 `client/app/src/main/assets/model-en-us/`（目录下直接是 `am/` `conf/` `graph/` `ivector/`）

或把 zip 交给脚本安装：

```bash
./scripts/fetch_vosk_model.sh /path/to/vosk-model-small-en-us-0.15.zip
```

然后打开 `client/` 用 Android Studio 构建，或：

```bash
cd client
./gradlew :app:assembleDebug
```

## Import the primary-school word list

Start the app once so the database exists, then:

```bash
cd server
python3 scripts/import_primary.py
```

The list is curriculum-oriented English (phonetics, Chinese gloss, short notes). Duplicates are skipped.

## Production

Example Gunicorn bind (port 5000), run from `server/`:

```bash
cd server
gunicorn --workers 1 --threads 4 --bind 0.0.0.0:5000 --timeout 60 app:app
```

Gunicorn does not reload on code changes. Restart the process (for example `supervisorctl restart words`) after you edit Python.

## Project layout

```
client/                     # Android app
  app/src/main/java/...
server/
  app.py                    # Flask app, routes
  db.py                     # SQLite connection and schema
  templates/                # Admin web pages
  static/css/style.css
  static/js/app.js          # Toast, TTS
  data/primary_school_words.py
  scripts/import_primary.py
  instance/                 # SQLite DB and secret key (not committed)
  requirements.txt
```

## Config

| Item | Notes |
| --- | --- |
| `DATABASE_PATH` | Optional path to the SQLite file. Default `server/instance/words.db`. |
| `DATABASE_URL` | Optional `sqlite:///…` URL (overrides default path). |
| `SECRET_KEY` | Optional env var. Otherwise `server/instance/secret_key` is generated. |
| `PORT` | Dev server port, default `5000`. |
| Daily quota | `users.daily_words` (new) and `users.daily_review` (familiar). Parents edit both in the app. |
