import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Configuração de testes separada do build (sem o plugin do Tailwind, desnecessário nos testes).
export default defineConfig({
  plugins: [react()],
  define: {
    global: 'window',
  },
  test: {
    environment: 'jsdom',
    // globals: a Testing Library registra sozinha o cleanup entre testes
    globals: true,
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    // Os pools "forks"/"threads" do Vitest 5.1 falham neste ambiente (Node 26 no Windows) com
    // "Cannot read properties of undefined (reading 'config')" até em um teste vazio;
    // o pool vmThreads funciona. Reavaliar ao atualizar o Vitest.
    pool: 'vmThreads',
  },
});
