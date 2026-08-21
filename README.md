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
- PostgreSQL (default `postgresql://moci:moci@127.0.0.1:5432/moci`)
- Gunicorn for production
- Mobile-first HTML/CSS (no frontend framework)

## Run locally

Start PostgreSQL first. On this host the `moci` database already exists. For a new machine:

```bash
docker compose up -d
```

Or create a local role and database named `moci`. Then:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export DATABASE_URL=postgresql://moci:moci@127.0.0.1:5432/moci
python3 app.py
```

Then open `http://127.0.0.1:5000`. The first visit creates tables if needed and a secret key under `instance/`.

The web login is for admins. Register the first account on the web (it becomes admin). Students and parents register in the Android app. Approve later users from **Users**.

If you still have `instance/words.db` and the PostgreSQL database is empty:

```bash
python3 scripts/migrate_sqlite.py
```

## Import the primary-school word list

Start the app once so the database exists, then:

```bash
python3 scripts/import_primary.py
```

The list is curriculum-oriented English (phonetics, Chinese gloss, short notes). Duplicates are skipped.

## Production

Example Gunicorn bind (port 5000):

```bash
gunicorn --workers 1 --threads 4 --bind 0.0.0.0:5000 --timeout 60 app:app
```

Gunicorn does not reload on code changes. Restart the process (for example `supervisorctl restart words`) after you edit Python.

## Project layout

```
app.py                      # Flask app, routes
db.py                       # PostgreSQL connection and schema
templates/                  # Admin web pages
static/css/style.css
static/js/app.js            # Toast, TTS
data/primary_school_words.py
scripts/import_primary.py
scripts/migrate_sqlite.py   # Optional SQLite → PostgreSQL copy
instance/                   # secret key (not committed)
```

## Config

| Item | Notes |
| --- | --- |
| `DATABASE_URL` | PostgreSQL URL. Default `postgresql://moci:moci@127.0.0.1:5432/moci`. |
| `SECRET_KEY` | Optional env var. Otherwise `instance/secret_key` is generated. |
| `PORT` | Dev server port, default `5000`. |
| Daily quota | `users.daily_words` (new) and `users.daily_review` (familiar). Parents edit both in the app. |
