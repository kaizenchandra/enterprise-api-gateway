alter table api_keys
    add column registry_value text;
create table api_key_events
(
    id         bigserial primary key,
    key_id     varchar(36) not null references api_keys (id),
    created_at timestamptz not null default now()
);
create index key_events_key on api_key_events (key_id);
