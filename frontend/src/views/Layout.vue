<template>
  <div class="h-screen min-h-0 flex overflow-hidden font-body-md bg-background text-on-surface">
    <!-- Mobile Navigation Drawer Overlay -->
    <div 
      v-if="mobileMenuOpen" 
      @click="mobileMenuOpen = false" 
      class="fixed inset-0 bg-black/40 z-30 md:hidden"
    ></div>

    <!-- Left Panel: SideNavBar -->
    <nav 
      class="bg-surface-container-lowest shadow-sm border-r border-outline-variant h-full w-64 flex-shrink-0 flex flex-col py-lg px-md z-40 fixed md:static transition-transform duration-300 md:translate-x-0"
      :class="[mobileMenuOpen ? 'translate-x-0' : '-translate-x-full md:translate-x-0']"
    >
      <!-- Header -->
      <div class="flex items-center gap-sm mb-lg">
        <div class="w-8 h-8 rounded-full border border-outline-variant bg-primary-container flex items-center justify-center text-on-primary">
          <span class="material-symbols-outlined text-[20px]" style="font-variation-settings: 'FILL' 1;">database</span>
        </div>
        <div>
          <h1 class="font-headline-md text-[18px] font-bold text-primary">企业知识库</h1>
          <p class="font-label-md text-label-md text-on-surface-variant">RAG Knowledge Hub</p>
        </div>
      </div>

      <!-- CTA: New Session -->
      <button 
        @click="createNewSession"
        class="w-full bg-primary text-on-primary hover:opacity-90 active:opacity-100 transition-opacity rounded-lg py-2 px-4 flex items-center justify-center gap-xs font-label-md text-label-md mb-lg shadow-sm cursor-pointer"
      >
        <span class="material-symbols-outlined text-[18px]" style="font-variation-settings: 'FILL' 1;">add</span>
        新建会话
      </button>

      <!-- Main Navigation Tabs -->
      <div class="flex-1 overflow-y-auto scrollbar-hide flex flex-col gap-xs">
        <div class="font-label-md text-label-md text-outline uppercase tracking-wider mb-sm px-2">功能导航</div>
        
        <router-link 
          to="/" 
          class="flex items-center gap-sm py-2 px-3 rounded-md hover:bg-surface-container-high transition-colors text-on-surface-variant font-bold text-left w-full group"
          exact-active-class="text-primary bg-surface-container border-r-4 border-primary"
        >
          <span class="material-symbols-outlined text-[20px]">add_comment</span>
          <span class="font-body-sm text-body-sm flex-1">智能对话</span>
        </router-link>

        <router-link 
          to="/documents" 
          class="flex items-center gap-sm py-2 px-3 rounded-md hover:bg-surface-container-high transition-colors text-on-surface-variant font-bold text-left w-full group"
          exact-active-class="text-primary bg-surface-container border-r-4 border-primary"
        >
          <span class="material-symbols-outlined text-[20px]">description</span>
          <span class="font-body-sm text-body-sm flex-1">文档管理</span>
        </router-link>

        <router-link 
          to="/profile" 
          class="flex items-center gap-sm py-2 px-3 rounded-md hover:bg-surface-container-high transition-colors text-on-surface-variant font-bold text-left w-full group"
          exact-active-class="text-primary bg-surface-container border-r-4 border-primary"
        >
          <span class="material-symbols-outlined text-[20px]">account_circle</span>
          <span class="font-body-sm text-body-sm flex-1">个人中心</span>
        </router-link>
      </div>

      <!-- Footer Navigation & Profile -->
      <div class="mt-auto pt-md border-t border-outline-variant flex flex-col gap-xs">
        <div class="flex items-center gap-sm py-sm px-xs mb-sm">
          <div class="w-8 h-8 rounded-full bg-primary text-on-primary flex items-center justify-center font-bold">
            {{ authStore.username ? authStore.username[0].toUpperCase() : 'U' }}
          </div>
          <div class="min-w-0 flex-1">
            <p class="font-body-sm text-body-sm font-semibold truncate">{{ authStore.username }}</p>
            <p class="font-label-md text-label-md text-outline truncate">{{ authStore.isAdmin ? '系统管理员' : '普通用户' }}</p>
          </div>
          <button 
            @click="triggerLogout" 
            class="text-outline-variant hover:text-error transition-colors cursor-pointer"
            title="退出登录"
          >
            <span class="material-symbols-outlined text-[20px]">logout</span>
          </button>
        </div>
      </div>
    </nav>

    <!-- Main Content Area -->
    <div class="flex-1 flex flex-col min-w-0 bg-background relative overflow-hidden">
      <!-- Top Header bar -->
      <header class="bg-surface-container-lowest border-b border-outline-variant flex justify-between items-center w-full h-16 px-gutter z-10 flex-shrink-0">
        <div class="flex items-center gap-sm">
          <!-- Mobile Menu Toggle -->
          <button 
            @click="mobileMenuOpen = !mobileMenuOpen" 
            class="md:hidden p-2 -ml-2 text-on-surface-variant hover:bg-surface-container-high rounded-full cursor-pointer"
          >
            <span class="material-symbols-outlined">menu</span>
          </button>
          
          <div>
            <h2 class="font-headline-md text-[18px] font-semibold text-on-surface truncate">
              {{ currentRouteTitle }}
            </h2>
            <p class="font-label-md text-label-md text-outline">RAG Knowledge Hub</p>
          </div>
        </div>

        <div class="flex items-center gap-sm">
          <span class="font-label-md text-label-md text-outline px-sm py-xs bg-surface-container rounded-md hidden sm:inline-block">
            用户名: {{ authStore.username }}
          </span>
          <div class="w-8 h-8 rounded-full border border-outline-variant bg-surface flex items-center justify-center font-bold text-primary">
            {{ authStore.username ? authStore.username[0].toUpperCase() : 'U' }}
          </div>
        </div>
      </header>

      <!-- Sub Page Router View -->
      <div class="flex-1 overflow-y-auto relative min-h-0">
        <router-view v-slot="{ Component }">
          <component :is="Component" :new-session-trigger="newSessionTrigger" @trigger-new-session="triggerNewSessionFlag" />
        </router-view>
      </div>
    </div>

    <!-- Custom Logout Confirmation Modal -->
    <div 
      v-if="showLogoutModal" 
      class="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-md"
    >
      <div @click.stop class="bg-surface-container-lowest border border-outline-variant rounded-xl w-full max-w-sm shadow-xl flex flex-col overflow-hidden animate-in fade-in zoom-in-95 duration-200">
        <header class="p-md border-b border-outline-variant flex justify-between items-center bg-surface-bright">
          <h3 class="font-headline-sm text-headline-sm text-on-background flex items-center gap-xs">
            <span class="material-symbols-outlined text-error">logout</span>
            退出登录
          </h3>
          <button 
            @click="cancelLogout"
            class="text-on-surface-variant hover:bg-surface-container-high p-1 rounded-md transition-colors cursor-pointer"
          >
            <span class="material-symbols-outlined">close</span>
          </button>
        </header>
        
        <main class="p-md text-on-surface">
          <p class="font-body-md text-body-md">您确定要退出当前账号的登录状态吗？</p>
        </main>
        
        <footer class="p-md border-t border-outline-variant flex justify-end gap-sm bg-surface-bright">
          <button 
            @click="cancelLogout" 
            class="px-4 py-2 border border-outline-variant hover:bg-surface-container-high transition-colors rounded-md text-on-surface-variant cursor-pointer font-label-md text-label-md"
          >
            取消
          </button>
          <button 
            @click="confirmLogout" 
            class="px-4 py-2 bg-error text-on-error hover:opacity-90 active:opacity-100 transition-opacity rounded-md cursor-pointer font-label-md text-label-md"
          >
            确定
          </button>
        </footer>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../store/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const mobileMenuOpen = ref(false)
const newSessionTrigger = ref(0) // incrementing number as an event flag

const currentRouteTitle = computed(() => {
  if (route.name === 'Chat') return '智能 AI 对话'
  if (route.name === 'Documents') return '知识库文档管理'
  if (route.name === 'Profile') return '个人中心'
  return 'RAG 知识库'
})

const showLogoutModal = ref(false)

const triggerLogout = () => {
  showLogoutModal.value = true
}

const confirmLogout = async () => {
  showLogoutModal.value = false
  await authStore.logout()
  router.push({ name: 'Login' })
}

const cancelLogout = () => {
  showLogoutModal.value = false
}

const createNewSession = () => {
  // If we are not on chat page, navigate to it first
  if (route.name !== 'Chat') {
    router.push({ name: 'Chat' })
  }
  // increment key to signal Chat.vue to create a new session
  newSessionTrigger.value++
}

const triggerNewSessionFlag = () => {
  newSessionTrigger.value++
}
</script>
