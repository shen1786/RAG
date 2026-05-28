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
          安全可靠的账户重置流程。输入注册的用户名即可获得临时重置码以安全更改您的密码。
        </p>
      </div>
    </div>

    <!-- Right Side: Reset Form -->
    <div class="w-full lg:w-1/2 flex flex-col justify-center items-center p-md md:p-xxl bg-surface-container-lowest">
      <div class="w-full max-w-md space-y-lg">
        <div>
          <h2 class="font-headline-lg text-headline-lg text-on-background">找回密码</h2>
          <p class="font-body-sm text-body-sm text-on-surface-variant mt-sm">
            {{ step === 1 ? '请输入您的用户名以请求验证码' : '请输入临时验证码及您的新密码' }}
          </p>
        </div>

        <!-- Error / Success Alerts -->
        <div v-if="errorMsg" class="p-sm bg-error-container border border-error text-error rounded-lg font-body-sm text-body-sm flex items-center gap-xs">
          <span class="material-symbols-outlined text-[18px]">error</span>
          <span>{{ errorMsg }}</span>
        </div>

        <div v-if="successMsg" class="p-sm bg-primary-fixed border border-primary-fixed-dim text-primary rounded-lg font-body-sm text-body-sm flex items-center gap-xs">
          <span class="material-symbols-outlined text-[18px]">check_circle</span>
          <span>{{ successMsg }}</span>
        </div>

        <!-- Step 1: Request Reset Code -->
        <form v-if="step === 1" @submit.prevent="handleRequestCode" class="space-y-md">
          <div class="space-y-xs">
            <label for="username" class="font-label-md text-label-md text-on-surface-variant block">用户名</label>
            <input 
              id="username"
              v-model="username" 
              type="text" 
              required
              placeholder="请输入您要找回密码的用户名" 
              class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
            />
          </div>

          <button 
            type="submit" 
            :disabled="loading"
            class="w-full bg-primary text-on-primary hover:opacity-90 active:opacity-100 disabled:opacity-50 transition-all rounded-lg py-sm font-label-md text-label-md flex justify-center items-center gap-xs cursor-pointer shadow-sm"
          >
            <span v-if="loading" class="material-symbols-outlined animate-spin text-[20px]">sync</span>
            <span>获取重置码</span>
          </button>
        </form>

        <!-- Step 2: Confirm Reset Password -->
        <form v-else @submit.prevent="handleConfirmReset" class="space-y-md">
          <div class="p-sm bg-surface-container border border-outline-variant text-on-surface rounded-lg font-body-sm text-body-sm">
            <p v-if="resetCodeHint" class="font-semibold text-primary">开发模式下返回了临时重置码：</p>
            <p v-if="resetCodeHint" class="font-mono text-lg mt-xs text-center border-2 border-dashed border-primary bg-surface py-1 rounded">{{ resetCodeHint }}</p>
            <p v-else class="font-semibold text-on-surface-variant flex items-center gap-xs">
              <span class="material-symbols-outlined text-[18px] text-primary">mail</span>
              <span>验证码已发送。请检查注册邮箱；若当前环境未配置 SMTP，请查看项目目录 `temp/emails` 下的模拟邮件或后端日志。</span>
            </p>
          </div>

          <div class="space-y-xs">
            <label for="resetCode" class="font-label-md text-label-md text-on-surface-variant block">重置码 (Reset Code)</label>
            <input 
              id="resetCode"
              v-model="resetCode" 
              type="text" 
              required
              placeholder="请输入重置验证码" 
              class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
            />
          </div>

          <div class="space-y-xs">
            <label for="newPassword" class="font-label-md text-label-md text-on-surface-variant block">新密码</label>
            <input 
              id="newPassword"
              v-model="newPassword" 
              type="password" 
              required
              placeholder="请输入新密码" 
              class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
            />
          </div>

          <div class="space-y-xs">
            <label for="confirmNewPassword" class="font-label-md text-label-md text-on-surface-variant block">确认新密码</label>
            <input 
              id="confirmNewPassword"
              v-model="confirmNewPassword" 
              type="password" 
              required
              placeholder="请再次确认新密码" 
              class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
            />
          </div>

          <button 
            type="submit" 
            :disabled="loading"
            class="w-full bg-primary text-on-primary hover:opacity-90 active:opacity-100 disabled:opacity-50 transition-all rounded-lg py-sm font-label-md text-label-md flex justify-center items-center gap-xs cursor-pointer shadow-sm"
          >
            <span v-if="loading" class="material-symbols-outlined animate-spin text-[20px]">sync</span>
            <span>重置密码</span>
          </button>
        </form>

        <div class="text-center pt-md border-t border-outline-variant">
          <p class="font-body-sm text-body-sm text-on-surface-variant">
            想起来密码了？ 
            <router-link to="/login" class="text-primary font-semibold hover:underline">返回登录</router-link>
          </p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { auth } from '../api'

const router = useRouter()

const step = ref(1)
const username = ref('')
const resetCode = ref('')
const newPassword = ref('')
const confirmNewPassword = ref('')
const loading = ref(false)
const errorMsg = ref('')
const successMsg = ref('')
const resetCodeHint = ref('')

const handleRequestCode = async () => {
  loading.value = true
  errorMsg.value = ''
  successMsg.value = ''
  try {
    const res = await auth.forgotRequest(username.value)
    if (res.code === 200) {
      resetCodeHint.value = res.data.resetCode || ''
      resetCode.value = res.data.resetCode || ''
      successMsg.value = res.data.resetCode
        ? '开发模式下已返回验证码。'
        : '验证码已发送，请检查邮箱或本地模拟邮件目录。'
      step.value = 2
    } else {
      errorMsg.value = res.message || '获取验证码失败'
    }
  } catch (err) {
    errorMsg.value = err.message || '网络错误，请稍后重试'
  } finally {
    loading.value = false
  }
}

const handleConfirmReset = async () => {
  if (newPassword.value !== confirmNewPassword.value) {
    errorMsg.value = '两次输入的密码不一致'
    return
  }
  if (newPassword.value.length < 6) {
    errorMsg.value = '新密码长度至少需要6位'
    return
  }

  loading.value = true
  errorMsg.value = ''
  successMsg.value = ''
  try {
    const res = await auth.forgotConfirm(
      username.value,
      resetCode.value,
      newPassword.value,
      confirmNewPassword.value
    )
    if (res.code === 200) {
      successMsg.value = '密码重置成功！即将为您跳转到登录页面...'
      setTimeout(() => {
        router.push({ name: 'Login' })
      }, 1500)
    } else {
      errorMsg.value = res.message || '重置密码失败'
    }
  } catch (err) {
    errorMsg.value = err.message || '重置密码失败'
  } finally {
    loading.value = false
  }
}
</script>
