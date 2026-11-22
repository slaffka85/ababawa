-- 0005_nickname.sql — display_name → nickname
--
-- Поле с самого начала хранило никнейм, а не «отображаемое имя»: его выбирает
-- сам пользователь при регистрации, по нему идёт поиск людей и приветствие в
-- письмах. Переименование колонки не переписывает данные: RENAME COLUMN
-- сохраняет все значения, а выражение индекса Postgres переписывает сам.
-- API и клиенты переходят на JSON-поле `nickname`.

ALTER TABLE users RENAME COLUMN display_name TO nickname;

-- Индекс по lower(nickname) продолжает работать; меняем только имя, чтобы оно
-- совпадало с колонкой (поиск /api/v1/users смотрит в индекс).
ALTER INDEX users_display_name_idx RENAME TO users_nickname_idx;
