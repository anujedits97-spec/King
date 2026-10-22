-- MYRA relay: lets the phone reach the laptop from anywhere, through your own Supabase project.
--
-- Paste this whole file into Supabase › SQL Editor › New query, and press Run. Safe to run again.
--
-- How it stays private: there is no login, so the phone and the laptop share one long secret
-- (made by the laptop). Rows are filed under the SHA-256 of that secret. The tables can't be read
-- or written directly by anyone - RLS is on with no policies and table access is revoked - and the
-- only way in is the functions below, which all require the secret. Someone with just the
-- project's public key can't list, read or add anything.

create extension if not exists pgcrypto with schema extensions;

create table if not exists public.myra_jobs (
  id uuid primary key default gen_random_uuid(),
  room text not null,
  kind text not null check (kind in ('ask', 'screenshot', 'reset')),
  input text not null default '' check (length(input) <= 4000),
  status text not null default 'pending' check (status in ('pending', 'working', 'done', 'error')),
  progress text,
  output jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists myra_jobs_room_status on public.myra_jobs (room, status, created_at);

create table if not exists public.myra_rooms (
  room text primary key,
  host text,
  last_seen timestamptz
);

alter table public.myra_jobs enable row level security;
alter table public.myra_rooms enable row level security;
revoke all on table public.myra_jobs from anon, authenticated;
revoke all on table public.myra_rooms from anon, authenticated;

-- The secret never touches a table: only its hash does.
create or replace function public.myra_room(secret text)
returns text language plpgsql immutable set search_path = '' as $$
begin
  if secret is null or length(secret) < 32 then
    raise exception 'myra: bad secret' using errcode = '28000';
  end if;
  return encode(extensions.digest(secret, 'sha256'), 'hex');
end $$;

-- Phone: ask the laptop something. Returns the job id to watch.
create or replace function public.myra_submit(secret text, p_kind text, p_input text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare r text := public.myra_room(secret); new_id uuid;
begin
  if (select count(*) from public.myra_jobs j where j.room = r and j.status in ('pending', 'working')) >= 10 then
    raise exception 'myra: too many jobs waiting';
  end if;
  insert into public.myra_jobs (room, kind, input) values (r, p_kind, coalesce(p_input, '')) returning id into new_id;
  return new_id;
end $$;

-- Phone: how is that job going?
create or replace function public.myra_get(secret text, job uuid)
returns table (status text, progress text, output jsonb, updated_at timestamptz)
language sql security definer set search_path = '' as $$
  select j.status, j.progress, j.output, j.updated_at
  from public.myra_jobs j
  where j.id = job and j.room = public.myra_room(secret)
$$;

-- Phone: is the laptop there? (It checks in every 20 seconds while the worker runs.)
create or replace function public.myra_status(secret text)
returns table (online boolean, host text, last_seen timestamptz)
language sql security definer set search_path = '' as $$
  select coalesce(r.last_seen > now() - interval '60 seconds', false), r.host, r.last_seen
  from (select public.myra_room(secret) as room) s
  left join public.myra_rooms r on r.room = s.room
$$;

-- Laptop: take the oldest waiting job, if any.
create or replace function public.myra_claim(secret text)
returns table (id uuid, kind text, input text)
language plpgsql security definer set search_path = '' as $$
declare r text := public.myra_room(secret);
begin
  return query
  update public.myra_jobs j set status = 'working', updated_at = now()
  where j.id = (
    select k.id from public.myra_jobs k
    where k.room = r and k.status = 'pending'
    order by k.created_at
    for update skip locked
    limit 1
  )
  returning j.id, j.kind, j.input;
end $$;

-- Laptop: report progress, the answer, or an error.
create or replace function public.myra_update(secret text, job uuid, new_status text, new_progress text, new_output jsonb)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if new_status not in ('working', 'done', 'error') then raise exception 'myra: bad status'; end if;
  if new_output is not null and pg_column_size(new_output) > 3000000 then raise exception 'myra: output too big'; end if;
  update public.myra_jobs j
  set status = new_status, progress = new_progress, output = coalesce(new_output, j.output), updated_at = now()
  where j.id = job and j.room = public.myra_room(secret);
end $$;

-- Laptop: "I'm here", every 20 seconds. Also tidies up this room: jobs older than a day are
-- deleted (screenshots included), and anything stuck for 30 minutes is marked failed.
create or replace function public.myra_beat(secret text, p_host text)
returns void language plpgsql security definer set search_path = '' as $$
declare r text := public.myra_room(secret);
begin
  insert into public.myra_rooms (room, host, last_seen) values (r, left(p_host, 64), now())
  on conflict (room) do update set host = excluded.host, last_seen = now();
  delete from public.myra_jobs j where j.room = r and j.created_at < now() - interval '1 day';
  update public.myra_jobs j set status = 'error', progress = 'Timed out', updated_at = now()
  where j.room = r and j.status in ('pending', 'working') and j.updated_at < now() - interval '30 minutes';
end $$;

-- A new job pokes the laptop over Supabase Realtime straight away (no waiting for the next check).
-- The channel name comes from the secret's hash, so nobody else can find it.
create or replace function public.myra_poke()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  begin
    perform realtime.send(jsonb_build_object('id', new.id), 'job', 'myra-' || new.room, false);
  exception when others then
    null; -- Realtime not available: the laptop still checks every few seconds.
  end;
  return new;
end $$;

drop trigger if exists myra_poke on public.myra_jobs;
create trigger myra_poke after insert on public.myra_jobs for each row execute function public.myra_poke();

revoke all on function public.myra_room(text) from public;
revoke all on function public.myra_submit(text, text, text) from public;
revoke all on function public.myra_get(text, uuid) from public;
revoke all on function public.myra_status(text) from public;
revoke all on function public.myra_claim(text) from public;
revoke all on function public.myra_update(text, uuid, text, text, jsonb) from public;
revoke all on function public.myra_beat(text, text) from public;
revoke all on function public.myra_poke() from public;
grant execute on function public.myra_submit(text, text, text) to anon, authenticated;
grant execute on function public.myra_get(text, uuid) to anon, authenticated;
grant execute on function public.myra_status(text) to anon, authenticated;
grant execute on function public.myra_claim(text) to anon, authenticated;
grant execute on function public.myra_update(text, uuid, text, text, jsonb) to anon, authenticated;
grant execute on function public.myra_beat(text, text) to anon, authenticated;
