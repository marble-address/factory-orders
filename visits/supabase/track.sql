-- نقطة موقع من تطبيق الأندرويد (أو الويب): بتتضاف لمستند track/{uid}_{day} — بينعمل لو مش موجود
create or replace function public.fs_track(p_day text, p_pt jsonb, p_name text default '', p_section text default 'rp') returns boolean
language plpgsql security definer set search_path = public as $$
declare c jsonb := fs_ctx(); u text := c->>'uid'; i text; o jsonb;
begin
  if u is null or not coalesce((c->>'user')::boolean, false) then raise exception 'permission-denied' using errcode = '42501'; end if;
  if p_day !~ '^\d{4}-\d{2}-\d{2}$' or jsonb_typeof(p_pt) <> 'object' then raise exception 'bad-request'; end if;
  i := u || '_' || p_day;
  select data into o from docs where col = 'track' and id = i for update;
  if o is null then
    insert into docs(col, id, data) values ('track', i, jsonb_build_object('uid', u, 'name', coalesce(p_name, ''), 'day', p_day,
      'section', coalesce(nullif(p_section, ''), 'rp'), 'points', jsonb_build_array(p_pt),
      'createdAt', (extract(epoch from clock_timestamp())*1000)::bigint));
  elsif not (coalesce(o->'points', '[]'::jsonb) @> jsonb_build_array(p_pt)) then
    update docs set data = jsonb_set(data, '{points}', coalesce(data->'points', '[]'::jsonb) || jsonb_build_array(p_pt)) where col = 'track' and id = i;
  end if;
  return true;
end $$;
revoke all on function public.fs_track(text, jsonb, text, text) from public, anon, authenticated;
grant execute on function public.fs_track(text, jsonb, text, text) to anon, authenticated;
