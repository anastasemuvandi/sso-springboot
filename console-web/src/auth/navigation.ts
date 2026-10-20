/** Full-page navigation, isolated so tests can observe it. */
export const browser = {
  assign(url: string) {
    window.location.assign(url);
  },
  currentPath(): string {
    return window.location.pathname + window.location.search + window.location.hash;
  },
};
