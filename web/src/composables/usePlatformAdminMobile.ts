import { onBeforeUnmount, onMounted, ref } from 'vue'

const mobileQuery = '(max-width: 767px)'

export function usePlatformAdminMobile() {
  const isMobile = ref(typeof window !== 'undefined'
    && (typeof window.matchMedia === 'function' ? window.matchMedia(mobileQuery).matches : window.innerWidth <= 767))
  let media: MediaQueryList | null = null
  let handleChange: ((event: MediaQueryListEvent) => void) | null = null
  let resizeHandler: (() => void) | null = null

  onMounted(() => {
    if (typeof window.matchMedia !== 'function') {
      resizeHandler = () => { isMobile.value = window.innerWidth <= 767 }
      window.addEventListener('resize', resizeHandler)
      return
    }
    media = window.matchMedia(mobileQuery)
    isMobile.value = media.matches
    handleChange = event => { isMobile.value = event.matches }
    if (typeof media.addEventListener === 'function') media.addEventListener('change', handleChange)
    else media.addListener(handleChange)
  })

  onBeforeUnmount(() => {
    if (resizeHandler) {
      window.removeEventListener('resize', resizeHandler)
      resizeHandler = null
    }
    if (!media || !handleChange) return
    if (typeof media.removeEventListener === 'function') media.removeEventListener('change', handleChange)
    else media.removeListener(handleChange)
    media = null
    handleChange = null
  })

  return isMobile
}
