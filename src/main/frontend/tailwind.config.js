/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        omnissa: {
          DEFAULT: '#1d6fa4',
          dark:    '#155a85',
          light:   '#e8f3fb',
        }
      }
    }
  },
  plugins: [],
}
