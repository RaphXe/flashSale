import { defineStore } from 'pinia'

const TOKEN_KEY = 'auth_token'

export const useAuthStore = defineStore('auth', {
    state: () => ({
        token: localStorage.getItem(TOKEN_KEY) || '',
        username: '',
        userId: '',
    }),
    getters: {
        isAuthenticated: (state) => Boolean(state.token),
    },
    actions: {
        setAuth({ token, username, userId }) {
            this.token = token
            this.username = username || ''
            this.userId = userId || ''

            if (token) {
                localStorage.setItem(TOKEN_KEY, token)
            }
        },
        clearAuth() {
            this.token = ''
            this.username = ''
            this.userId = ''
            localStorage.removeItem(TOKEN_KEY)
        },
    },
})
