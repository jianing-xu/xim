export function Avatar({ name, group = false, id }: { name: string; group?: boolean; id?: string }) {
  return <span id={id} className={`avatar${group ? ' group' : ''}`} aria-hidden="true">
    {group ? '#' : name.slice(0, 1).toUpperCase()}
  </span>;
}
