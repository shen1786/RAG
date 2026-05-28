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
          加入 RAG 知识库，开启您的文档解析和智能对话之旅。支持企业私有向量库托管、高精排Rerank模型检索。
        </p>
      </div>
    </div>

    <!-- Right Side: Register Form -->
    <div class="w-full lg:w-1/2 flex flex-col justify-center items-center p-md md:p-xxl bg-surface-container-lowest">
      <div class="w-full max-w-md space-y-lg">
        <div>
          <h2 class="font-headline-lg text-headline-lg text-on-background">创建新账户</h2>
          <p class="font-body-sm text-body-sm text-on-surface-variant mt-sm">请填写真实有效的信息进行注册</p>
        </div>

        <form @submit.prevent="handleRegister" class="space-y-md">
          <div v-if="errorMsg" class="p-sm bg-error-container border border-error text-error rounded-lg font-body-sm text-body-sm flex items-center gap-xs">
            <span class="material-symbols-outlined text-[18px]">error</span>
            <span>{{ errorMsg }}</span>
          </div>

          <div v-if="successMsg" class="p-sm bg-primary-fixed border border-primary-fixed-dim text-primary rounded-lg font-body-sm text-body-sm flex items-center gap-xs">
            <span class="material-symbols-outlined text-[18px]">check_circle</span>
            <span>{{ successMsg }}</span>
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
            <label for="email" class="font-label-md text-label-md text-on-surface-variant block">邮箱</label>
            <input
              id="email"
              v-model="email"
              type="email"
              required
              placeholder="请输入可接收找回邮件的邮箱"
              class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
            />
          </div>

          <div class="space-y-xs">
            <label for="password" class="font-label-md text-label-md text-on-surface-variant block">密码</label>
            <input 
              id="password"
              v-model="password" 
              type="password" 
              required
              placeholder="请输入密码 (至少6位)" 
              class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
            />
          </div>

          <div class="space-y-xs">
            <label for="confirmPassword" class="font-label-md text-label-md text-on-surface-variant block">确认密码</label>
            <input 
              id="confirmPassword"
              v-model="confirmPassword" 
              type="password" 
              required
              placeholder="请再次输入密码" 
              class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
            />
          </div>

          <div class="space-y-xs">
            <label for="roleCode" class="font-label-md text-label-md text-on-surface-variant block">账户角色</label>
            <select 
              id="roleCode"
              v-model="roleCode" 
              class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
            >
              <option value="user">普通用户 (User)</option>
              <option value="admin">系统管理员 (Admin)</option>
            </select>
          </div>

          <button 
            type="submit" 
            :disabled="loading"
            class="w-full bg-primary text-on-primary hover:opacity-90 active:opacity-100 disabled:opacity-50 transition-all rounded-lg py-sm font-label-md text-label-md flex justify-center items-center gap-xs cursor-pointer shadow-sm"
          >
            <span v-if="loading" class="material-symbols-outlined animate-spin text-[20px]">sync</span>
            <span>{{ loading ? '注册中...' : '注 册' }}</span>
          </button>
        </form>

        <div class="text-center pt-md border-t border-outline-variant">
          <p class="font-body-sm text-body-sm text-on-surface-variant">
            已经有账户？ 
            <router-link to="/login" class="text-primary font-semibold hover:underline">立即登录</router-link>
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
const email = ref('')
const password = ref('')
const confirmPassword = ref('')
const roleCode = ref('user')
const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')

const handleRegister = async () => {
  if (password.value !== confirmPassword.value) {
    errorMsg.value = '两次输入的密码不一致'
    return
  }
  if (password.value.length < 6) {
    errorMsg.value = '密码长度不能少于6位'
    return
  }
  if (!email.value.trim()) {
    errorMsg.value = '邮箱不能为空'
    return
  }

  loading.value = true
  errorMsg.value = ''
  successMsg.value = ''

  try {
    await authStore.register(username.value, password.value, email.value, roleCode.value)
    successMsg.value = '注册成功！即将为您跳转到登录页面...'
    setTimeout(() => {
      router.push({ name: 'Login' })
    }, 1500)
  } catch (err) {
    errorMsg.value = err.message || '注册失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>
