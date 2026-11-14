/**
 * ============================================================================
 * Cliente HTTP Axios Centralizado (api.ts)
 * ============================================================================
 * Gerencia todas as chamadas REST para o Backend Spring Boot (porta 8085),
 * injetando automaticamente o token JWT Bearer em cada requisição e tratando
 * expiração de sessão (HTTP 401).
 */
import axios from 'axios';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8085/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
});

// Interceptor de Requisição: Anexa o JWT armazenado no localStorage
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Interceptor de Resposta: Redireciona para login caso o token expire (401)
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);
