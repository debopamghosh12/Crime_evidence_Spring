import axios from "axios";

// Single source of truth for the backend's base URL. Every page imports THIS instance instead of
// bare axios, so there is exactly one place that knows where the Spring Boot backend lives (never
// the source repo's :3000 fallback or :3001 rewrite - both pointed at things that don't exist here).
export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

const api = axios.create({
    baseURL: API_BASE_URL,
});

export default api;
