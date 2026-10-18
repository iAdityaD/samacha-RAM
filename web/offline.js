export function overlay(state,operations){
 const next=structuredClone(state);
 for(const {path,body:b} of operations){
  if(path==='/api/profile')next.profile={...next.profile,...Object.fromEntries(Object.entries(b).filter(([k])=>k!=='operation_id'))};
  if(path==='/api/save'){
   next.saved=next.saved.filter(s=>s.id!==b.id).concat(b.active===false?[]:[{id:b.id,note:b.note??next.saved.find(s=>s.id===b.id)?.note??'',created:new Date().toISOString()}]);
  }
 }
 return next;
}
export async function replay(operations,send,onAcknowledged){
 for(const operation of [...operations]){await send(operation.path,operation.body);onAcknowledged(operation.body.operation_id);}
}
