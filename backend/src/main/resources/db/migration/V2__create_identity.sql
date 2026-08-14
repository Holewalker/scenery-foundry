CREATE TABLE users (
    id uuid PRIMARY KEY,
    email varchar(320) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL CHECK (password_hash <> '')
);
