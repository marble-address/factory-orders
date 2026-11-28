-- جسر مؤقت: نقط الموقع اللي تطبيق الأندرويد القديم (النسخة 2/3) لسه بيكتبها في Firestore بتتنسخ هنا
create or replace function public.srv_track_merge(p_secret text, p_id text, p_data jsonb) returns integer
language plpgsql security definer set search_path = public as $$
declare o jsonb; pts jsonb; p jsonb; added integer := 0;
begin
  if not srv_ok(p_secret) then raise exception 'denied'; end if;
  select data into o from docs where col = 'track' and id = p_id for update;
  if o is null then
    insert into docs(col, id, data) values ('track', p_id, p_data);
    return coalesce(jsonb_array_length(p_data->'points'), 0);
  end if;
  pts := coalesce(o->'points', '[]'::jsonb);
  for p in select * from jsonb_array_elements(coalesce(p_data->'points', '[]'::jsonb)) loop
    if not exists (select 1 from jsonb_array_elements(pts) x where x->>'t' = p->>'t') then pts := pts || jsonb_build_array(p); added := added + 1; end if;
  end loop;
  if added > 0 then
    select coalesce(jsonb_agg(x order by (x->>'t')::bigint), '[]'::jsonb) into pts from jsonb_array_elements(pts) x;
    update docs set data = jsonb_set(data, '{points}', pts) where col = 'track' and id = p_id;
  end if;
  return added;
end $$;
revoke all on function public.srv_track_merge(text, text, jsonb) from public, anon, authenticated;
grant execute on function public.srv_track_merge(text, text, jsonb) to anon, authenticated;
