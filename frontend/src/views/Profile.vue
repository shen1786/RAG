<template>
  <div class="p-md md:p-lg max-w-4xl mx-auto space-y-lg pb-xl">
    <!-- Header -->
    <header class="flex flex-col gap-xs">
      <h1 class="font-headline-lg text-headline-lg text-on-background">个人中心</h1>
      <p class="font-body-sm text-body-sm text-on-surface-variant">
        查看您的账户基本信息，或在这里修改账户登录密码。
      </p>
    </header>

    <!-- Alert Messages -->
    <div v-if="errorMsg" class="p-sm bg-error-container border border-error text-error rounded-lg font-body-sm text-body-sm flex items-center gap-xs">
      <span class="material-symbols-outlined text-[18px]">error</span>
      <span>{{ errorMsg }}</span>
    </div>

    <div v-if="successMsg" class="p-sm bg-primary-fixed border border-primary-fixed-dim text-primary rounded-lg font-body-sm text-body-sm flex items-center gap-xs">
      <span class="material-symbols-outlined text-[18px]">check_circle</span>
      <span>{{ successMsg }}</span>
    </div>

    <div class="grid grid-cols-1 md:grid-cols-3 gap-lg">
      <!-- Left Column: User Profile Info -->
      <div class="md:col-span-1 space-y-md">
        <div class="bg-surface-container-lowest border border-outline-variant rounded-xl p-lg text-center shadow-sm space-y-sm">
          <div class="w-20 h-20 mx-auto rounded-full border border-outline-variant bg-primary-container text-on-primary flex items-center justify-center font-bold text-2xl">
            {{ authStore.username ? authStore.username[0].toUpperCase() : 'U' }}
          </div>
          <div>
            <h3 class="font-headline-sm text-headline-sm text-on-background font-semibold">{{ authStore.username }}</h3>
            <p class="font-label-md text-label-md text-outline uppercase tracking-wide mt-xs">
              {{ authStore.isAdmin ? '系统管理员' : '普通用户' }}
            </p>
          </div>
          <div class="pt-md border-t border-outline-variant flex flex-col gap-xs text-left">
            <div>
              <span class="font-label-md text-label-md text-outline">用户 ID</span>
              <p class="font-body-sm text-body-sm font-semibold truncate select-all">{{ authStore.userId }}</p>
            </div>
            <div>
              <span class="font-label-md text-label-md text-outline">注册邮箱</span>
              <p class="font-body-sm text-body-sm font-semibold truncate select-all">{{ authStore.email || '未设置' }}</p>
            </div>
            <div>
              <span class="font-label-md text-label-md text-outline">系统角色</span>
              <div class="flex flex-wrap gap-xs mt-xs">
                <span 
                  v-for="role in authStore.roles" 
                  :key="role"
                  class="bg-surface-container text-on-surface-variant font-label-sm text-label-sm px-2 py-0.5 rounded-full"
                >
                  {{ role }}
                </span>
              </div>
            </div>
          </div>
        </div>


      </div>

      <!-- Right Column: Forms -->
      <div class="md:col-span-2 space-y-lg">
        <!-- Form 1: Change Password -->
        <div class="bg-surface-container-lowest border border-outline-variant rounded-xl p-lg shadow-sm space-y-md">
          <h3 class="font-headline-sm text-headline-sm text-on-background font-semibold border-b border-outline-variant pb-xs">
            修改登录密码
          </h3>
          <form @submit.prevent="handleChangePassword" class="space-y-md">
            <div class="space-y-xs">
              <label for="currentPassword" class="font-label-md text-label-md text-on-surface-variant block">当前密码</label>
              <input 
                id="currentPassword"
                v-model="changePassForm.currentPassword" 
                type="password" 
                required
                placeholder="请输入您当前的登录密码" 
                class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
              />
            </div>
            
            <div class="space-y-xs">
              <label for="newPassword" class="font-label-md text-label-md text-on-surface-variant block">新密码</label>
              <input 
                id="newPassword"
                v-model="changePassForm.newPassword" 
                type="password" 
                required
                placeholder="请输入新密码 (至少6位)" 
                class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
              />
            </div>

            <div class="space-y-xs">
              <label for="confirmNewPassword" class="font-label-md text-label-md text-on-surface-variant block">确认新密码</label>
              <input 
                id="confirmNewPassword"
                v-model="changePassForm.confirmNewPassword" 
                type="password" 
                required
                placeholder="请再次确认新密码" 
                class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
              />
            </div>

            <div class="flex justify-end pt-xs">
              <button 
                type="submit" 
                :disabled="submittingChange"
                class="px-md py-sm bg-primary text-on-primary rounded-lg font-label-md text-label-md hover:opacity-90 active:opacity-100 disabled:opacity-50 transition-all cursor-pointer shadow-sm flex items-center gap-xs"
              >
                <span v-if="submittingChange" class="material-symbols-outlined animate-spin text-[18px]">sync</span>
                确认修改密码
              </button>
            </div>
          </form>
        </div>

        <!-- Form 2: Admin Password Reset (Visible only to authorized admins) -->
        <div 
          v-if="authStore.isAdmin || authStore.permissions.includes('user:password:reset')"
          class="bg-surface-container-lowest border border-outline-variant rounded-xl p-lg shadow-sm space-y-md border-l-4 border-l-primary"
        >
          <div>
            <h3 class="font-headline-sm text-headline-sm text-on-background font-semibold">
              管理员重置密码
            </h3>
            <p class="font-label-md text-label-md text-outline mt-xs">此功能仅系统管理员可见。可以直接重置其他用户的密码。</p>
          </div>
          <form @submit.prevent="handleAdminReset" class="space-y-md border-t border-outline-variant pt-md">
            <div class="space-y-xs">
              <label for="resetUsername" class="font-label-md text-label-md text-on-surface-variant block">目标用户名 (Target Username)</label>
              <input 
                id="resetUsername"
                v-model="adminResetForm.username" 
                type="text" 
                required
                placeholder="请输入要重置密码的用户名" 
                class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
              />
            </div>

            <div class="space-y-xs">
              <label for="adminNewPassword" class="font-label-md text-label-md text-on-surface-variant block">设定新密码</label>
              <input 
                id="adminNewPassword"
                v-model="adminResetForm.newPassword" 
                type="password" 
                required
                placeholder="请输入为该用户设定的新密码" 
                class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
              />
            </div>

            <div class="space-y-xs">
              <label for="adminConfirmNewPassword" class="font-label-md text-label-md text-on-surface-variant block">确认设定新密码</label>
              <input 
                id="adminConfirmNewPassword"
                v-model="adminResetForm.confirmNewPassword" 
                type="password" 
                required
                placeholder="请再次确认新密码" 
                class="w-full px-md py-sm border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm"
              />
            </div>

            <div class="flex justify-end pt-xs">
              <button 
                type="submit" 
                :disabled="submittingReset"
                class="px-md py-sm bg-primary text-on-primary rounded-lg font-label-md text-label-md hover:opacity-90 active:opacity-100 disabled:opacity-50 transition-all cursor-pointer shadow-sm flex items-center gap-xs"
              >
                <span v-if="submittingReset" class="material-symbols-outlined animate-spin text-[18px]">sync</span>
                强制重置密码
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useAuthStore } from '../store/auth'
import { auth } from '../api'

const authStore = useAuthStore()

const errorMsg = ref('')
const successMsg = ref('')

const changePassForm = reactive({
  currentPassword: '',
  newPassword: '',
  confirmNewPassword: ''
})
const submittingChange = ref(false)

const adminResetForm = reactive({
  username: '',
  newPassword: '',
  confirmNewPassword: ''
})
const submittingReset = ref(false)

const handleChangePassword = async () => {
  if (changePassForm.newPassword !== changePassForm.confirmNewPassword) {
    errorMsg.value = '两次输入的新密码不一致'
    return
  }
  if (changePassForm.newPassword.length < 6) {
    errorMsg.value = '新密码长度至少需要6位'
    return
  }

  submittingChange.value = true
  errorMsg.value = ''
  successMsg.value = ''

  try {
    const res = await auth.changePassword(
      changePassForm.currentPassword,
      changePassForm.newPassword,
      changePassForm.confirmNewPassword
    )
    if (res.code === 200) {
      successMsg.value = '您的登录密码修改成功！'
      // Clear inputs
      changePassForm.currentPassword = ''
      changePassForm.newPassword = ''
      changePassForm.confirmNewPassword = ''
    } else {
      errorMsg.value = res.message || '修改密码失败'
    }
  } catch (err) {
    errorMsg.value = err.message || '密码修改失败，请检查当前密码是否输入正确'
  } finally {
    submittingChange.value = false
  }
}

const handleAdminReset = async () => {
  if (adminResetForm.newPassword !== adminResetForm.confirmNewPassword) {
    errorMsg.value = '两次输入的重置密码不一致'
    return
  }
  if (adminResetForm.newPassword.length < 6) {
    errorMsg.value = '重置密码长度至少需要6位'
    return
  }

  submittingReset.value = true
  errorMsg.value = ''
  successMsg.value = ''

  try {
    const res = await auth.resetPassword(
      adminResetForm.username,
      adminResetForm.newPassword,
      adminResetForm.confirmNewPassword
    )
    if (res.code === 200) {
      successMsg.value = `用户 ${adminResetForm.username} 的密码重置成功！`
      // Clear inputs
      adminResetForm.username = ''
      adminResetForm.newPassword = ''
      adminResetForm.confirmNewPassword = ''
    } else {
      errorMsg.value = res.message || '重置密码失败'
    }
  } catch (err) {
    errorMsg.value = err.message || '重置密码发生网络异常，请重试'
  } finally {
    submittingReset.value = false
  }
}
</script>
