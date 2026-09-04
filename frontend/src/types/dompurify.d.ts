declare module 'dompurify' {
  interface SanitizeConfig {
    USE_PROFILES?: { html?: boolean }
  }

  const DOMPurify: {
    sanitize(value: string, config?: SanitizeConfig): string
  }

  export default DOMPurify
}
