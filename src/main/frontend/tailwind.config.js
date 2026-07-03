/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        omnissa: {
          DEFAULT: '#132250',
          dark:    '#0c1636',
          light:   '#e9ecf5',
        }
      }
    }
  },
  plugins: [],
}
