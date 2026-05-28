<template>
  <div class="h-screen flex overflow-hidden font-body-md bg-surface text-on-surface">
    <!-- Left Side: Branding & Visuals (Hidden on mobile) -->
    <div class="hidden lg:flex lg:w-1/2 bg-surface-container-low relative overflow-hidden flex-col justify-center items-center p-xxl border-r border-outline-variant">
      <div class="absolute inset-0 bg-pattern"></div>
      <div class="relative z-10 max-w-lg text-center space-y-lg">
        <div class="w-24 h-24 mx-auto rounded-xl shadow-sm mb-lg border border-outline-variant bg-surface-container-lowest flex items-center justify-center">
          <span class="material-symbols-outlined text-primary text-[48px]">database</span>
        </div>
        <h1 class="font-display-lg text-display-lg text-primary">RAG Knowledge Hub</h1>
        <p class="font-body-lg text-body-lg text-on-surface-variant leading-relaxed">
          企业级多模态知识库与智能问答系统。提供文档的高效解析、高密度向量检索、流式问答以及长期记忆的用户画像分析。
        </p>
      </div>
    </div>

    <!-- Right Side: Login Form -->
    <div class="w-full lg:w-1/2 flex flex-col justify-center items-center p-md md:p-xxl bg-surface-container-lowest">
      <div class="w-full max-w-md space-y-lg">
        <div>
          <h2 class="font-headline-lg text-headline-lg text-on-background">欢迎回来</h2>
          <p class="font-body-sm text-body-sm text-on-surface-variant mt-sm">请登录您的账户以开始使用知识库</p>
        </div>

        <form @submit.prevent="handleLogin" class="space-y-md">
          <div v-if="errorMsg" class="p-sm bg-error-container border border-error text-error rounded-lg font-body-sm text-body-sm flex items-center gap-xs">
            <span class="material-symbols-outlined text-[18px]">error</span>
            <span>{{ errorMsg }}</span>
          </div>

          <div class="space-y-xs">
            <label for="username" class="font-label-md text-label-md text-on-surface-variant block">用户名</label>
            <input 
              id="username"
              v-model="username" 
              type="text" 
              required
              placeholder="请输入用户名" 
              class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
            />
          </div>

          <div class="space-y-xs">
            <div class="flex justify-between items-center">
              <label for="password" class="font-label-md text-label-md text-on-surface-variant block">密码</label>
              <router-link to="/forgot" class="font-label-md text-label-md text-primary hover:underline">忘记密码？</router-link>
            </div>
            <input 
              id="password"
              v-model="password" 
              type="password" 
              required
              placeholder="请输入密码" 
              class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
            />
          </div>

          <button 
            type="submit" 
            :disabled="loading"
            class="w-full bg-primary text-on-primary hover:opacity-90 active:opacity-100 disabled:opacity-50 transition-all rounded-lg py-sm font-label-md text-label-md flex justify-center items-center gap-xs cursor-pointer shadow-sm"
          >
            <span v-if="loading" class="material-symbols-outlined animate-spin text-[20px]">sync</span>
            <span>{{ loading ? '登录中...' : '登 录' }}</span>
          </button>
        </form>

        <div class="text-center pt-md border-t border-outline-variant">
          <p class="font-body-sm text-body-sm text-on-surface-variant">
            还没有账户？ 
            <router-link to="/register" class="text-primary font-semibold hover:underline">立即注册</router-link>
          </p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/auth'

const router = useRouter()
const authStore = useAuthStore()

const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMsg = ref('')

const handleLogin = async () => {
  loading.value = true
  errorMsg.value = ''
  try {
    await authStore.login(username.value, password.value)
    router.push({ name: 'Chat' })
  } catch (err) {
    errorMsg.value = err.message || '用户名或密码错误'
  } finally {
    loading.value = false
  }
}
</script>
