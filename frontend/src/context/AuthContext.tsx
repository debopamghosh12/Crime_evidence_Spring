"use client";

import React, { createContext, useContext, useEffect, useState } from "react";
import api from "@/lib/api";
import { useRouter } from "next/navigation";

// Matches com.blockevidence.backend.dto.MeResponse exactly (GET /api/auth/me). No "username" field -
// the real backend identifies users by email; role is one of the 6 backend Role enum constants
// (COLLECTOR, FORENSIC_ANALYST, PROSECUTOR, JUDGE, AUDITOR, ADMIN), not the source repo's role names.
interface User {
    id: string;
    email: string;
    fullName: string;
    department?: string;
    role: string;
}

interface AuthContextType {
    user: User | null;
    accessToken: string | null;
    /** @deprecated alias for accessToken, kept only until every page below is migrated off it (Part A) */
    token: string | null;
    // Real backend login is two calls: POST /api/auth/login (returns tokens only, no profile) then
    // GET /api/auth/me (returns the profile). login() takes both tokens plus the already-fetched user.
    login: (accessToken: string, refreshToken: string, user: User) => void;
    logout: () => void;
    isAuthenticated: boolean;
    isLoading: boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
    const [user, setUser] = useState<User | null>(null);
    const [accessToken, setAccessToken] = useState<string | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const router = useRouter();

    useEffect(() => {
        // Check sessionStorage for existing session (Tab Specific)
        const storedAccessToken = sessionStorage.getItem("accessToken");
        const storedUser = sessionStorage.getItem("user");

        if (storedAccessToken && storedUser) {
            // eslint-disable-next-line react-hooks/set-state-in-effect
            setAccessToken(storedAccessToken);
            setUser(JSON.parse(storedUser));
            api.defaults.headers.common["Authorization"] = `Bearer ${storedAccessToken}`;
        }
        setIsLoading(false);
    }, []);

    const login = (newAccessToken: string, newRefreshToken: string, newUser: User) => {
        setAccessToken(newAccessToken);
        setUser(newUser);
        sessionStorage.setItem("accessToken", newAccessToken);
        sessionStorage.setItem("refreshToken", newRefreshToken);
        sessionStorage.setItem("user", JSON.stringify(newUser));
        api.defaults.headers.common["Authorization"] = `Bearer ${newAccessToken}`;
        // Redirect to dynamic user dashboard
        router.push(`/dashboard/${newUser.id}`);
    };

    const logout = () => {
        setAccessToken(null);
        setUser(null);
        sessionStorage.removeItem("accessToken");
        sessionStorage.removeItem("refreshToken");
        sessionStorage.removeItem("user");
        delete api.defaults.headers.common["Authorization"];
        router.push("/login");
    };

    return (
        <AuthContext.Provider
            value={{
                user,
                accessToken,
                token: accessToken,
                login,
                logout,
                isAuthenticated: !!accessToken,
                isLoading,
            }}
        >
            {children}
        </AuthContext.Provider>
    );
}

export function useAuth() {
    const context = useContext(AuthContext);
    if (context === undefined) {
        throw new Error("useAuth must be used within an AuthProvider");
    }
    return context;
}
