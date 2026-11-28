-- دوال السيرفر (سكربت الإشعارات في Apps Script) — محمية بكلمة سر في app_secrets (k='push')
create or replace function public.srv_ok(p_secret text) returns boolean
language sql stable security definer set search_path = public as $$
  select p_secret is not null and p_secret = (select v from app_secrets where k = 'push')
$$;
create or replace function public.srv_get(p_secret text, p_col text, p_id text) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare d jsonb;
begin
  if not srv_ok(p_secret) then raise exception 'denied'; end if;
  select data into d from docs where col = p_col and id = p_id;
  return case when d is null then null else d || jsonb_build_object('id', p_id) end;
end $$;
create or replace function public.srv_query(p_secret text, p_col text, p_filters jsonb default '[]'::jsonb) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare r jsonb;
begin
  if not srv_ok(p_secret) then raise exception 'denied'; end if;
  select coalesce(jsonb_agg(data || jsonb_build_object('id', id)), '[]'::jsonb) into r from docs where col = p_col and fs_match(data, p_filters);
  return r;
end $$;
create or replace function public.srv_set(p_secret text, p_col text, p_id text, p_data jsonb) returns text
language plpgsql security definer set search_path = public as $$
declare i text := coalesce(nullif(p_id, ''), replace(gen_random_uuid()::text, '-', ''));
begin
  if not srv_ok(p_secret) then raise exception 'denied'; end if;
  insert into docs(col, id, data) values (p_col, i, fs_fix(p_data) - 'id') on conflict (col, id) do update set data = excluded.data;
  return i;
end $$;
create or replace function public.srv_update(p_secret text, p_col text, p_id text, p_patch jsonb) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  if not srv_ok(p_secret) then raise exception 'denied'; end if;
  update docs set data = data || (fs_fix(p_patch) - 'id') where col = p_col and id = p_id;
  return found;
end $$;
revoke all on function public.srv_ok(text), public.srv_get(text,text,text), public.srv_query(text,text,jsonb),
  public.srv_set(text,text,text,jsonb), public.srv_update(text,text,text,jsonb) from public, anon, authenticated;
grant execute on function public.srv_get(text,text,text), public.srv_query(text,text,jsonb),
  public.srv_set(text,text,text,jsonb), public.srv_update(text,text,text,jsonb) to anon, authenticated;
