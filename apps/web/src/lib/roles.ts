/** Display text for the API's role names. */
export function roleLabel(role: string): string {
  return role.charAt(0) + role.slice(1).toLowerCase();
}

/** "an admin", "a viewer". */
export function withArticle(role: string): string {
  return `${/^[AEIOU]/.test(role) ? "an" : "a"} ${role.toLowerCase()}`;
}
