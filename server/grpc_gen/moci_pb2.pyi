from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ClientMessage(_message.Message):
    __slots__ = ("hello", "ping")
    HELLO_FIELD_NUMBER: _ClassVar[int]
    PING_FIELD_NUMBER: _ClassVar[int]
    hello: Hello
    ping: Ping
    def __init__(self, hello: _Optional[_Union[Hello, _Mapping]] = ..., ping: _Optional[_Union[Ping, _Mapping]] = ...) -> None: ...

class Hello(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: str
    def __init__(self, session: _Optional[str] = ...) -> None: ...

class Ping(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ServerMessage(_message.Message):
    __slots__ = ("ready", "settings_updated", "pong", "error", "words_updated")
    READY_FIELD_NUMBER: _ClassVar[int]
    SETTINGS_UPDATED_FIELD_NUMBER: _ClassVar[int]
    PONG_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    WORDS_UPDATED_FIELD_NUMBER: _ClassVar[int]
    ready: Ready
    settings_updated: SettingsUpdated
    pong: Pong
    error: Error
    words_updated: WordsUpdated
    def __init__(self, ready: _Optional[_Union[Ready, _Mapping]] = ..., settings_updated: _Optional[_Union[SettingsUpdated, _Mapping]] = ..., pong: _Optional[_Union[Pong, _Mapping]] = ..., error: _Optional[_Union[Error, _Mapping]] = ..., words_updated: _Optional[_Union[WordsUpdated, _Mapping]] = ...) -> None: ...

class Ready(_message.Message):
    __slots__ = ("user_id",)
    USER_ID_FIELD_NUMBER: _ClassVar[int]
    user_id: int
    def __init__(self, user_id: _Optional[int] = ...) -> None: ...

class UserSettings(_message.Message):
    __slots__ = ("id", "username", "role", "status", "daily_words", "daily_review", "know_speak", "know_spell", "know_pos", "know_phonetic", "reward_minutes")
    ID_FIELD_NUMBER: _ClassVar[int]
    USERNAME_FIELD_NUMBER: _ClassVar[int]
    ROLE_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    DAILY_WORDS_FIELD_NUMBER: _ClassVar[int]
    DAILY_REVIEW_FIELD_NUMBER: _ClassVar[int]
    KNOW_SPEAK_FIELD_NUMBER: _ClassVar[int]
    KNOW_SPELL_FIELD_NUMBER: _ClassVar[int]
    KNOW_POS_FIELD_NUMBER: _ClassVar[int]
    KNOW_PHONETIC_FIELD_NUMBER: _ClassVar[int]
    REWARD_MINUTES_FIELD_NUMBER: _ClassVar[int]
    id: int
    username: str
    role: str
    status: str
    daily_words: int
    daily_review: int
    know_speak: bool
    know_spell: bool
    know_pos: bool
    know_phonetic: bool
    reward_minutes: int
    def __init__(self, id: _Optional[int] = ..., username: _Optional[str] = ..., role: _Optional[str] = ..., status: _Optional[str] = ..., daily_words: _Optional[int] = ..., daily_review: _Optional[int] = ..., know_speak: bool = ..., know_spell: bool = ..., know_pos: bool = ..., know_phonetic: bool = ..., reward_minutes: _Optional[int] = ...) -> None: ...

class SettingsUpdated(_message.Message):
    __slots__ = ("user",)
    USER_FIELD_NUMBER: _ClassVar[int]
    user: UserSettings
    def __init__(self, user: _Optional[_Union[UserSettings, _Mapping]] = ...) -> None: ...

class WordsUpdated(_message.Message):
    __slots__ = ("action", "word_id")
    ACTION_FIELD_NUMBER: _ClassVar[int]
    WORD_ID_FIELD_NUMBER: _ClassVar[int]
    action: str
    word_id: int
    def __init__(self, action: _Optional[str] = ..., word_id: _Optional[int] = ...) -> None: ...

class Pong(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Error(_message.Message):
    __slots__ = ("code", "message")
    CODE_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    code: str
    message: str
    def __init__(self, code: _Optional[str] = ..., message: _Optional[str] = ...) -> None: ...

class ApiInvokeRequest(_message.Message):
    __slots__ = ("method", "path", "session", "csrf_token", "body_json", "query")
    class QueryEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    METHOD_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    SESSION_FIELD_NUMBER: _ClassVar[int]
    CSRF_TOKEN_FIELD_NUMBER: _ClassVar[int]
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    method: str
    path: str
    session: str
    csrf_token: str
    body_json: str
    query: _containers.ScalarMap[str, str]
    def __init__(self, method: _Optional[str] = ..., path: _Optional[str] = ..., session: _Optional[str] = ..., csrf_token: _Optional[str] = ..., body_json: _Optional[str] = ..., query: _Optional[_Mapping[str, str]] = ...) -> None: ...

class ApiInvokeResponse(_message.Message):
    __slots__ = ("ok", "error", "message", "body_json", "http_status", "session", "csrf_token")
    OK_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    BODY_JSON_FIELD_NUMBER: _ClassVar[int]
    HTTP_STATUS_FIELD_NUMBER: _ClassVar[int]
    SESSION_FIELD_NUMBER: _ClassVar[int]
    CSRF_TOKEN_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    error: str
    message: str
    body_json: str
    http_status: int
    session: str
    csrf_token: str
    def __init__(self, ok: bool = ..., error: _Optional[str] = ..., message: _Optional[str] = ..., body_json: _Optional[str] = ..., http_status: _Optional[int] = ..., session: _Optional[str] = ..., csrf_token: _Optional[str] = ...) -> None: ...
