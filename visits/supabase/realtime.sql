-- تحديث لحظي: أي تغيير في docs بيتبعت إشارة صغيرة (القسم/النوع/الرقم بس — من غير بيانات) على قناة «mv»
-- التطبيق لما يستقبلها بيعيد قراءة الشاشة المفتوحة من دوال fs_* (بنفس الصلاحيات)
create or replace function public.docs_notify() returns trigger
language plpgsql security definer set search_path = public as $$
declare r record; ign text[] := array['lastSeen','notifAt','pushed','pushedAt'];
begin
  r := coalesce(new, old);
  if tg_op = 'UPDATE' and (new.data - ign) = (old.data - ign) then return null; end if;   -- نبض «متصل» وعلامات الإرسال: مش تغيير يستاهل
  perform realtime.send(
    jsonb_build_object('col', r.col, 'id', r.id, 'op', lower(tg_op),
      'uid', coalesce(r.data->>'uid', r.data->>'repUid', r.data->>'ownerUid', r.data->>'toUid', '')),
    'chg', 'mv', false);
  return null;
end $$;
drop trigger if exists docs_notify on public.docs;
create trigger docs_notify after insert or update or delete on public.docs for each row execute function public.docs_notify();
