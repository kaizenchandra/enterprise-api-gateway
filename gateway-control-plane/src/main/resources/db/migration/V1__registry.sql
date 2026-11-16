create table registry
(
    kind       varchar(32) not null,
    id         varchar(64) not null,
    revision   bigint      not null,
    body       jsonb       not null,
    updated_at timestamptz not null default now(),
    primary key (kind, id)
);
create sequence configuration_version_seq;
create table configuration_versions
(
    version    bigint primary key,
    body       jsonb       not null,
    created_at timestamptz not null default now()
);
create table api_keys
(
    id          varchar(36) primary key,
    consumer_id varchar(64) not null,
    verifier    char(64)    not null,
    expires_at  timestamptz not null,
    revoked     boolean     not null default false
);
create index keys_consumer on api_keys (consumer_id);
create table audit_events
(
    id         bigserial primary key,
    actor      varchar(256) not null,
    action     varchar(64)  not null,
    target     varchar(256) not null,
    created_at timestamptz  not null default now()
);
create index audit_time on audit_events (created_at);
