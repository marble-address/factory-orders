-- استيراد البيانات من Firestore (مرة واحدة) — محمود بس
create or replace function public.fs_import(p_col text, p_rows jsonb) returns integer
language plpgsql security definer set search_path = public as $$
declare r jsonb; n integer := 0;
begin
  if fs_uid() is null or coalesce(auth.jwt()->>'email','') <> 'mahmoud@marble-visits.app' then raise exception 'denied'; end if;
  for r in select * from jsonb_array_elements(p_rows) loop
    insert into docs(col, id, data) values (p_col, r->>'id', r->'data')
      on conflict (col, id) do update set data = excluded.data;
    n := n + 1;
  end loop;
  return n;
end $$;
revoke all on function public.fs_import(text, jsonb) from public, anon, authenticated;
grant execute on function public.fs_import(text, jsonb) to anon, authenticated;
