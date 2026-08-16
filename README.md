# Moci (墨词)

A mobile-first English vocabulary app for primary-school learners. Students study a shared word bank; parents set daily goals and check progress; admins manage users and words.

The UI is in Chinese. This document is in English.

## Roles

| Role | What they do |
| --- | --- |
| **Student** | Study new words and review words already at the **familiar** stage. **I know it** requires typing the spelling. |
| **Parent** | View bound children’s progress, set daily **new-word** and **review** quotas, and switch into a child’s account without a password. |
| **Admin** | Approve or reject sign-ups, maintain the shared word bank, bind children to parents, and view learning reports. Admins do not study words. |

The first registered user becomes admin automatically. Later student and parent accounts stay **pending** until an admin approves them.

## Study rules

- Students do not have a word-bank browser. They only use **Home**, **Study**, and **Me**.
- Each day a student has two tasks: **new words** and **review of familiar (了解) words**.
- Parents set both daily amounts on **Tasks** (0–50 each, default 8).
- New word + **I know it** + correct spelling → **familiar (了解)**.
- Familiar word + **I know it** + correct spelling → **mastered (掌握)**.
- **I don’t know it** → the word returns to the new-word pool.
- Learning reports split activity into **new-word study** vs **familiar-word review**.

## Account switching

On **Me**:

- **Parent → child:** one tap, no password.
- **Child → parent:** the child must enter that parent’s login password.
- Switching is limited to accounts already bound by an admin.

## Stack

- Python 3 + Flask
- SQLite (`instance/words.db`)
- Gunicorn for production
- Mobile-first HTML/CSS (no frontend framework)

## Run locally

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python3 app.py
```

Then open `http://127.0.0.1:5000`. The first visit creates the database and a secret key under `instance/`.

Register the first account (it becomes admin). Approve later users from **Users**.

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
app.py                      # Flask app, routes, SQLite schema
templates/                  # Pages
static/css/style.css
static/js/app.js            # Flashcards, spelling check
data/primary_school_words.py
scripts/import_primary.py
instance/                   # DB and secret key (not committed)
```

## Config

| Item | Notes |
| --- | --- |
| `SECRET_KEY` | Optional env var. Otherwise `instance/secret_key` is generated. |
| `PORT` | Dev server port, default `5000`. |
| Daily quota | `users.daily_words` (new) and `users.daily_review` (familiar). Parents edit both on **Tasks**. |
