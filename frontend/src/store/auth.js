import { defineStore } from 'pinia'
import { auth } from '../api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('token') || null,
    user: JSON.parse(localStorage.getItem('user')) || null
  }),
  getters: {
    isAuthenticated: (state) => !!state.token,
    userId: (state) => state.user?.userId || null,
    username: (state) => state.user?.username || '',
    permissions: (state) => state.user?.permissions || [],
    roles: (state) => state.user?.roles || [],
    email: (state) => state.user?.email || '',
    isAdmin: (state) => state.user?.roles?.includes('admin') || state.user?.permissions?.includes('admin')
  },
  actions: {
    async login(username, password) {
      try {
        const res = await auth.login(username, password)
        if (res.code === 200) {
          const { tokenValue, userId, username: resUsername, email, roles, permissions } = res.data
          this.token = tokenValue
          this.user = { userId, username: resUsername, email, roles, permissions }
          localStorage.setItem('token', tokenValue)
          localStorage.setItem('user', JSON.stringify(this.user))
          return res.data
        } else {
          throw new Error(res.message || '登录失败')
        }
      } catch (err) {
        throw err
      }
    },
    async register(username, password, email) {
      try {
        const res = await auth.register(username, password, email)
        if (res.code !== 200) {
          throw new Error(res.message || '注册失败')
        }
        return res.data
      } catch (err) {
        throw err
      }
    },
    async logout() {
      try {
        if (this.token) {
          await auth.logout()
        }
      } catch (err) {
        console.error('Logout error:', err)
      } finally {
        this.token = null
        this.user = null
        localStorage.removeItem('token')
        localStorage.removeItem('user')
      }
    },
    async fetchUser() {
      try {
        const res = await auth.me()
        if (res.code === 200) {
          const { userId, username, email, status, roles, permissions } = res.data
          this.user = { userId, username, email, status, roles, permissions }
          localStorage.setItem('user', JSON.stringify(this.user))
          return this.user
        }
      } catch (err) {
        console.error('Fetch user info error:', err)
        this.logout()
      }
    }
  }
})
