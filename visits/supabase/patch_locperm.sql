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


-- تحديث لحظي: أي تغيير في docs بيتبعت إشارة صغيرة (القسم/النوع/الرقم بس — من غير بيانات) على قناة «mv»
-- التطبيق لما يستقبلها بيعيد قراءة الشاشة المفتوحة من دوال fs_* (بنفس الصلاحيات)
create or replace function public.docs_notify() returns trigger
language plpgsql security definer set search_path = public as $$
declare r record; ign text[] := array['lastSeen','notifAt','pushed','pushedAt','locAt'];
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
