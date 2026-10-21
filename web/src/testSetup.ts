// jsdom does not perform layout. Supply browser observer APIs while keeping
// Element Plus components and their event/model behavior real in component tests.
class TestResizeObserver implements ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

globalThis.ResizeObserver = TestResizeObserver
window.matchMedia ??= (query: string) => ({
  matches: false,
  media: query,
  onchange: null,
  addListener() {},
  removeListener() {},
  addEventListener() {},
  removeEventListener() {},
  dispatchEvent: () => false,
})

HTMLElement.prototype.scrollIntoView ??= function () {}
