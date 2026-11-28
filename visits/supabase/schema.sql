-- «قسم المشاريع» على Supabase — بديل Firestore
-- كل المستندات في جدول واحد (col, id, data jsonb). مفيش وصول مباشر للجدول:
-- كل القراءة والكتابة من خلال دوال fs_* بتطبق نفس قواعد firestore.rules بالظبط.
-- تسجيل الدخول لسه Firebase Auth (Third-party auth): المستخدم = auth.jwt()->>'sub'

create table if not exists public.docs (
  col  text not null,
  id   text not null,
  data jsonb not null default '{}'::jsonb,
  primary key (col, id)
);
alter table public.docs enable row level security;   -- من غير policies = مقفول تمامًا على anon/authenticated
revoke all on public.docs from anon, authenticated;

create index if not exists docs_rep   on public.docs (col, (data->>'repUid'));
create index if not exists docs_owner on public.docs (col, (data->>'ownerUid'));
create index if not exists docs_cust  on public.docs (col, (data->>'customerId'));
create index if not exists docs_uid   on public.docs (col, (data->>'uid'));
create index if not exists docs_to    on public.docs (col, (data->>'toUid'));
create index if not exists docs_day   on public.docs (col, (data->>'day'));
create index if not exists docs_push  on public.docs (col, ((data->>'pushed'))) where col = 'notifs';

create table if not exists public.app_secrets (k text primary key, v text not null);
alter table public.app_secrets enable row level security;
revoke all on public.app_secrets from anon, authenticated;

-- ---------- هوية المستخدم ----------
create or replace function public.fs_uid() returns text
language sql stable as $$
  select case when coalesce(auth.jwt()->>'iss','') = 'https://securetoken.google.com/marble-visits'
               and coalesce(auth.jwt()->>'aud','') = 'marble-visits'
              then nullif(auth.jwt()->>'sub','') end
$$;

-- سياق المستخدم: {uid, email, role, sec, active, top, secadm}
create or replace function public.fs_ctx() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare u text := fs_uid(); m jsonb; s text;
begin
  if u is null then return jsonb_build_object('uid', null); end if;
  select data into m from docs where col = 'users' and id = u;
  s := coalesce(m->>'section', '');
  return jsonb_build_object(
    'uid', u, 'email', auth.jwt()->>'email',
    'user', m is not null and coalesce((m->>'active')::boolean, false),
    'role', m->>'role', 'sec', s,
    'top', m is not null and coalesce((m->>'active')::boolean,false) and m->>'role' = 'admin' and s in ('', 'all'),
    'secadm', m is not null and coalesce((m->>'active')::boolean,false) and m->>'role' = 'admin' and s not in ('', 'all'));
end $$;

create or replace function public.fs_insec(c jsonb, d jsonb) returns boolean
language sql immutable as $$ select coalesce(d->>'section', 'rp') = (c->>'sec') $$;

create or replace function public.fs_keys_changed(o jsonb, n jsonb) returns text[]
language sql immutable as $$
  select coalesce(array_agg(k), '{}') from (
    select k from (select jsonb_object_keys(o) k union select jsonb_object_keys(n)) x
    where (o->k) is distinct from (n->k)) y
$$;

create or replace function public.fs_can_read(c jsonb, col text, id text, d jsonb) returns boolean
language plpgsql immutable as $$
declare uid text := c->>'uid'; usr boolean := coalesce((c->>'user')::boolean,false);
  top boolean := coalesce((c->>'top')::boolean,false); sa boolean := coalesce((c->>'secadm')::boolean,false);
  rep boolean := usr and c->>'role' = 'rep'; eng boolean := usr and c->>'role' = 'eng';
  sr boolean := sa and fs_insec(c, d);
begin
  if uid is null then return false; end if;
  case col
    when 'users' then return top or sr or id = uid or (usr and d->>'role' = 'eng');
    when 'customers', 'comments' then return top or sr or (rep and d->>'ownerUid' = uid);
    when 'visits', 'photos', 'contacts', 'tasks' then return top or sr or (rep and d->>'repUid' = uid);
    when 'notifs' then return top or (usr and d->>'toUid' = uid) or (sr and d->>'toUid' = 'ADMINS');
    when 'attendance', 'track' then return top or sr or (usr and d->>'uid' = uid);
    when 'engreq' then return top or sr or eng or (rep and d->>'repUid' = uid);
    else return false;
  end case;
end $$;

create or replace function public.fs_can_create(c jsonb, col text, id text, n jsonb) returns boolean
language plpgsql immutable as $$
declare uid text := c->>'uid'; usr boolean := coalesce((c->>'user')::boolean,false);
  top boolean := coalesce((c->>'top')::boolean,false); sa boolean := coalesce((c->>'secadm')::boolean,false);
  rep boolean := usr and c->>'role' = 'rep'; sn boolean := sa and fs_insec(c, n);
begin
  if uid is null then return false; end if;
  case col
    when 'users' then return (top and n->>'role' in ('rep','admin','eng')) or (sn and n->>'role' = 'rep')
                        or (id = uid and c->>'email' = 'mahmoud@marble-visits.app' and n->>'role' = 'admin');
    when 'customers' then return top or sn or (rep and n->>'ownerUid' = uid);
    when 'visits', 'photos', 'contacts', 'engreq' then return top or sn or (rep and n->>'repUid' = uid);
    when 'comments' then return n->>'authorUid' = uid and (top or sn or (rep and n->>'ownerUid' = uid));
    when 'tasks' then return top or sn;
    when 'notifs' then return usr and n->>'fromUid' = uid and coalesce((n->>'pushed')::boolean, true) = false;
    when 'attendance', 'track' then return top or sn or (rep and n->>'uid' = uid and id = uid || '_' || (n->>'day'));
    else return false;
  end case;
end $$;

create or replace function public.fs_can_update(c jsonb, col text, id text, o jsonb, n jsonb) returns boolean
language plpgsql immutable as $$
declare uid text := c->>'uid'; usr boolean := coalesce((c->>'user')::boolean,false);
  top boolean := coalesce((c->>'top')::boolean,false); sa boolean := coalesce((c->>'secadm')::boolean,false);
  rep boolean := usr and c->>'role' = 'rep'; eng boolean := usr and c->>'role' = 'eng';
  se boolean := sa and fs_insec(c, o) and fs_insec(c, n); k text[] := fs_keys_changed(o, n);
begin
  if uid is null then return false; end if;
  if top then return true; end if;
  case col
    when 'users' then return (se and o->>'role' = 'rep' and n->>'role' = 'rep')
      or (id = uid and k <@ array['lastSeen','fcmTokens','notifOn','notifAt','mustSetPw','locPerm','device','locAt'] and coalesce((n->>'mustSetPw')::boolean, false) = false);
    when 'customers' then return se or (rep and o->>'ownerUid' = uid and n->>'ownerUid' = uid);
    when 'visits', 'photos', 'contacts', 'comments' then return se;
    when 'tasks' then return se or (rep and o->>'repUid' = uid and k <@ array['status','result','doneAt']);
    when 'notifs' then return (se and o->>'toUid' = 'ADMINS' and k <@ array['readBy'])
      or (usr and o->>'toUid' = uid and k <@ array['read']);
    when 'attendance' then return se or (rep and o->>'uid' = uid and k <@ array['points','outAt','outLoc']);
    when 'track' then return se or (rep and o->>'uid' = uid and k <@ array['points']);
    when 'engreq' then return se
      or (eng and k <@ array['status','due','result','photos','thread','doneAt','doneBy','engUid'])
      or (rep and o->>'repUid' = uid and k <@ array['thread']);
    else return false;
  end case;
end $$;

create or replace function public.fs_can_delete(c jsonb, col text, o jsonb) returns boolean
language plpgsql immutable as $$
declare top boolean := coalesce((c->>'top')::boolean,false); sa boolean := coalesce((c->>'secadm')::boolean,false);
begin
  if c->>'uid' is null then return false; end if;
  case col
    when 'users' then return false;
    when 'notifs', 'attendance', 'track' then return top;
    else return top or (sa and fs_insec(c, o));
  end case;
end $$;

-- «__SV__» = وقت السيرفر بالمللي ثانية
create or replace function public.fs_fix(d jsonb) returns jsonb
language sql volatile as $$
  select coalesce(jsonb_object_agg(key, case when value = '"__SV__"'::jsonb
           then to_jsonb((extract(epoch from clock_timestamp()) * 1000)::bigint) else value end), '{}'::jsonb)
  from jsonb_each(coalesce(d, '{}'::jsonb))
$$;

-- ---------- الدوال اللي التطبيق بينادي عليها ----------
create or replace function public.fs_get(p_col text, p_id text) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare c jsonb := fs_ctx(); d jsonb;
begin
  select data into d from docs where col = p_col and id = p_id;
  if d is null or not fs_can_read(c, p_col, p_id, d) then return null; end if;
  return d || jsonb_build_object('id', p_id);
end $$;

-- الفلاتر: [[field, op, value], ...] — op: == != > >= < <= in array-contains
create or replace function public.fs_match(d jsonb, f jsonb) returns boolean
language plpgsql immutable as $$
declare e jsonb; fld text; op text; v jsonb; x jsonb;
begin
  for e in select * from jsonb_array_elements(coalesce(f, '[]'::jsonb)) loop
    fld := e->>0; op := e->>1; v := e->2; x := d->fld;
    if op = '==' then if x is distinct from v then return false; end if;
    elsif op = '!=' then if x is not distinct from v then return false; end if;
    elsif op = 'in' then if x is null or not exists (select 1 from jsonb_array_elements(v) a where a = x) then return false; end if;
    elsif op = 'array-contains' then if x is null or jsonb_typeof(x) <> 'array' or not (x @> jsonb_build_array(v)) then return false; end if;
    elsif op in ('>', '>=', '<', '<=') then
      if x is null or jsonb_typeof(x) <> jsonb_typeof(v) then return false; end if;
      if jsonb_typeof(v) = 'number' then
        if not (case op when '>' then (x::text)::numeric > (v::text)::numeric when '>=' then (x::text)::numeric >= (v::text)::numeric
                        when '<' then (x::text)::numeric < (v::text)::numeric else (x::text)::numeric <= (v::text)::numeric end) then return false; end if;
      else
        if not (case op when '>' then x #>> '{}' > v #>> '{}' when '>=' then x #>> '{}' >= v #>> '{}'
                        when '<' then x #>> '{}' < v #>> '{}' else x #>> '{}' <= v #>> '{}' end) then return false; end if;
      end if;
    else return false;
    end if;
  end loop;
  return true;
end $$;

create or replace function public.fs_query(p_col text, p_filters jsonb default '[]'::jsonb) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare c jsonb := fs_ctx(); r jsonb; eqf jsonb := '{}'::jsonb; e jsonb;
begin
  if c->>'uid' is null then raise exception 'permission-denied' using errcode = '42501'; end if;
  -- فلاتر == بتتحول لـ @> عشان الاستعلام يبقى سريع
  for e in select * from jsonb_array_elements(coalesce(p_filters, '[]'::jsonb)) loop
    if e->>1 = '==' and jsonb_typeof(e->2) in ('string','number','boolean') then eqf := eqf || jsonb_build_object(e->>0, e->2); end if;
  end loop;
  select coalesce(jsonb_agg(d.data || jsonb_build_object('id', d.id)), '[]'::jsonb) into r
  from docs d
  where d.col = p_col and d.data @> eqf and fs_match(d.data, p_filters) and fs_can_read(c, p_col, d.id, d.data);
  return r;
end $$;

-- إضافة أو استبدال مستند (زي set في Firestore). p_id فاضي = id جديد
create or replace function public.fs_set(p_col text, p_id text, p_data jsonb) returns text
language plpgsql security definer set search_path = public as $$
declare c jsonb := fs_ctx(); i text := coalesce(nullif(p_id, ''), replace(gen_random_uuid()::text, '-', '')); o jsonb; n jsonb := fs_fix(p_data) - 'id';
begin
  select data into o from docs where col = p_col and id = i for update;
  if o is null then
    if not fs_can_create(c, p_col, i, n) then raise exception 'permission-denied' using errcode = '42501'; end if;
    insert into docs(col, id, data) values (p_col, i, n);
  else
    if not fs_can_update(c, p_col, i, o, n) then raise exception 'permission-denied' using errcode = '42501'; end if;
    update docs set data = n where col = p_col and id = i;
  end if;
  return i;
end $$;

create or replace function public.fs_add_many(p_col text, p_rows jsonb) returns integer
language plpgsql security definer set search_path = public as $$
declare r jsonb; n integer := 0;
begin
  for r in select * from jsonb_array_elements(p_rows) loop perform fs_set(p_col, null, r); n := n + 1; end loop;
  return n;
end $$;

-- تعديل حقول (زي update). p_union: {field: [قيم]} = arrayUnion
create or replace function public.fs_update(p_col text, p_id text, p_patch jsonb, p_union jsonb default null) returns boolean
language plpgsql security definer set search_path = public as $$
declare c jsonb := fs_ctx(); o jsonb; n jsonb; k text; vals jsonb; cur jsonb; v jsonb;
begin
  select data into o from docs where col = p_col and id = p_id for update;
  if o is null then raise exception 'not-found' using errcode = 'P0002'; end if;
  n := o || (fs_fix(p_patch) - 'id');
  if p_union is not null then
    for k, vals in select * from jsonb_each(p_union) loop
      cur := case when jsonb_typeof(n->k) = 'array' then n->k else '[]'::jsonb end;
      for v in select * from jsonb_array_elements(vals) loop
        if not (cur @> jsonb_build_array(v)) then cur := cur || jsonb_build_array(v); end if;
      end loop;
      n := n || jsonb_build_object(k, cur);
    end loop;
  end if;
  if not fs_can_update(c, p_col, p_id, o, n) then raise exception 'permission-denied' using errcode = '42501'; end if;
  update docs set data = n where col = p_col and id = p_id;
  return true;
end $$;

create or replace function public.fs_delete(p_col text, p_id text) returns boolean
language plpgsql security definer set search_path = public as $$
declare c jsonb := fs_ctx(); o jsonb;
begin
  select data into o from docs where col = p_col and id = p_id;
  if o is null then return true; end if;
  if not fs_can_delete(c, p_col, o) then raise exception 'permission-denied' using errcode = '42501'; end if;
  delete from docs where col = p_col and id = p_id;
  return true;
end $$;

create or replace function public.fs_whoami() returns jsonb
language sql stable security definer set search_path = public as $$ select fs_ctx() $$;

-- ---------- إرسال الإشعارات (Apps Script) ----------
-- بيرجع الإشعارات اللي لسه ما اتبعتتش + توكنات المستلمين، وبيعلّمها pushed
create or replace function public.push_pending(p_secret text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare r jsonb := '[]'::jsonb; n record; toks jsonb; nsec text;
begin
  if p_secret is null or p_secret <> (select v from app_secrets where k = 'push') then raise exception 'denied'; end if;
  for n in select id, data from docs where col = 'notifs' and data->>'pushed' = 'false' order by id limit 50 for update skip locked loop
    nsec := coalesce(n.data->>'section', 'rp');
    select coalesce(jsonb_agg(t), '[]'::jsonb) into toks from (
      select distinct jsonb_array_elements_text(u.data->'fcmTokens') t from docs u
      where u.col = 'users' and jsonb_typeof(u.data->'fcmTokens') = 'array' and (
        (n.data->>'toUid' = 'ADMINS' and u.data->>'role' = 'admin' and coalesce(u.data->>'section','') in ('', 'all', nsec))
        or u.id = n.data->>'toUid')) x;
    r := r || jsonb_build_array(jsonb_build_object('id', n.id, 'title', n.data->>'title', 'body', n.data->>'body',
            'customerId', n.data->>'customerId', 'tokens', toks));
    update docs set data = data || jsonb_build_object('pushed', true, 'pushedAt', (extract(epoch from clock_timestamp())*1000)::bigint)
      where col = 'notifs' and id = n.id;
  end loop;
  return r;
end $$;

create or replace function public.push_drop_token(p_secret text, p_token text) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  if p_secret is null or p_secret <> (select v from app_secrets where k = 'push') then raise exception 'denied'; end if;
  update docs set data = jsonb_set(data, '{fcmTokens}', coalesce((select jsonb_agg(t) from jsonb_array_elements(data->'fcmTokens') t where t <> to_jsonb(p_token)), '[]'::jsonb))
    where col = 'users' and data->'fcmTokens' @> jsonb_build_array(p_token);
  return true;
end $$;

-- الصلاحيات: الدوال بس
revoke all on all functions in schema public from public, anon, authenticated;
grant execute on function public.fs_get(text, text), public.fs_query(text, jsonb), public.fs_set(text, text, jsonb),
  public.fs_add_many(text, jsonb), public.fs_update(text, text, jsonb, jsonb), public.fs_delete(text, text),
  public.fs_whoami(), public.push_pending(text), public.push_drop_token(text, text) to anon, authenticated;
