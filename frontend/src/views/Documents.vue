<template>
  <div class="p-md md:p-lg space-y-md relative h-full">
    <!-- Header -->
    <header class="flex flex-col gap-xs">
      <h1 class="font-headline-lg text-headline-lg text-on-background">文档库管理</h1>
      <p class="font-body-sm text-body-sm text-on-surface-variant">
        管理知识库中的源文档，支持普通直传与大文件分片断点续传。解析及删除均为异步状态流。
      </p>
    </header>

    <!-- Toolbar -->
    <div class="bg-surface-container-lowest border border-outline-variant rounded-xl p-sm flex flex-col md:flex-row gap-sm justify-between items-center shadow-sm">
      <div class="flex flex-col sm:flex-row gap-sm w-full md:w-auto">
        <div class="relative w-full sm:w-64">
          <span class="material-symbols-outlined absolute left-sm top-1/2 -translate-y-1/2 text-outline text-[18px]">search</span>
          <input 
            v-model="searchKeyword" 
            @input="debouncedSearch"
            type="text" 
            placeholder="搜索文档名称..." 
            class="w-full pl-xl pr-sm py-xs border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm text-on-background placeholder:text-outline-variant"
          />
        </div>
        <div class="relative w-full sm:w-40">
          <select 
            v-model="filterType"
            @change="loadDocuments"
            class="w-full pl-sm pr-xl py-xs border border-outline-variant rounded-lg bg-surface focus:border-primary focus:ring-2 focus:ring-primary-container outline-none transition-all font-body-sm text-body-sm text-on-background appearance-none"
          >
            <option value="">全部类型</option>
            <option value="TEXT">文本/文档</option>
            <option value="IMAGE">图片/多模态</option>
            <option value="VIDEO">视频音频</option>
          </select>
          <span class="material-symbols-outlined absolute right-sm top-1/2 -translate-y-1/2 text-outline pointer-events-none text-[18px]">arrow_drop_down</span>
        </div>
      </div>
      <button 
        @click="showUploadModal = true"
        class="w-full md:w-auto flex items-center justify-center gap-xs bg-primary text-on-primary px-md py-xs rounded-lg font-label-md text-label-md hover:opacity-90 active:opacity-100 transition-opacity cursor-pointer shadow-sm"
      >
        <span class="material-symbols-outlined text-[16px]">upload_file</span>
        上传文档
      </button>
    </div>

    <!-- Data Table -->
    <div class="bg-surface-container-lowest border border-outline-variant rounded-xl overflow-hidden shadow-sm">
      <div class="overflow-x-auto">
        <table class="w-full text-left border-collapse">
          <thead>
            <tr class="bg-surface-container border-b border-outline-variant">
              <th class="p-sm pl-md font-label-sm text-label-sm text-on-surface-variant whitespace-nowrap uppercase tracking-wider">文件名</th>
              <th class="p-sm font-label-sm text-label-sm text-on-surface-variant whitespace-nowrap uppercase tracking-wider">类型</th>
              <th class="p-sm font-label-sm text-label-sm text-on-surface-variant whitespace-nowrap uppercase tracking-wider">大小</th>
              <th class="p-sm font-label-sm text-label-sm text-on-surface-variant whitespace-nowrap uppercase tracking-wider">切片数</th>
              <th class="p-sm font-label-sm text-label-sm text-on-surface-variant whitespace-nowrap uppercase tracking-wider">向量状态</th>
              <th class="p-sm font-label-sm text-label-sm text-on-surface-variant whitespace-nowrap uppercase tracking-wider">上传时间</th>
              <th class="p-sm pr-md font-label-sm text-label-sm text-on-surface-variant whitespace-nowrap text-right uppercase tracking-wider">操作</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-outline-variant font-body-sm text-body-sm text-on-background">
            <tr v-if="loadingList" class="hover:bg-transparent">
              <td colspan="7" class="text-center py-xxl text-outline">
                <div class="flex items-center justify-center gap-xs">
                  <span class="material-symbols-outlined animate-spin">sync</span>
                  加载数据中...
                </div>
              </td>
            </tr>
            <tr v-else-if="documentsList.length === 0" class="hover:bg-transparent">
              <td colspan="7" class="text-center py-xxl text-outline">
                暂无相关文档
              </td>
            </tr>
            <tr 
              v-else
              v-for="doc in documentsList" 
              :key="doc.id"
              class="hover:bg-surface-container-low transition-colors group"
            >
              <td class="p-sm pl-md flex items-center gap-xs">
                <span class="material-symbols-outlined text-outline" style="font-variation-settings: 'FILL' 1;">
                  {{ getFileIcon(doc.filename) }}
                </span>
                <span class="font-medium truncate max-w-[200px] md:max-w-xs" :title="doc.filename">{{ doc.filename }}</span>
              </td>
              <td class="p-sm text-on-surface-variant">{{ getFileExtension(doc.filename) }}</td>
              <td class="p-sm text-on-surface-variant tabular-nums">{{ formatBytes(doc.size) }}</td>
              <td class="p-sm text-on-surface-variant tabular-nums">{{ doc.chunksCount || '-' }}</td>
              <td class="p-sm">
                <div 
                  class="inline-flex items-center gap-xs border rounded px-xs py-xs"
                  :class="getStatusClasses(doc.status)"
                >
                  <span v-if="isDocProcessing(doc.status)" class="material-symbols-outlined text-[12px] animate-spin">sync</span>
                  <div v-else class="w-1.5 h-1.5 rounded-full" :class="[doc.status === 'SUCCESS' ? 'bg-primary' : 'bg-error']"></div>
                  <span class="font-label-sm text-label-sm">{{ formatStatus(doc.status) }}</span>
                </div>
              </td>
              <td class="p-sm text-on-surface-variant tabular-nums">{{ formatDateTime(doc.createdAt) }}</td>
              <td class="p-sm pr-md text-right">
                <div class="flex items-center justify-end gap-xs opacity-60 group-hover:opacity-100 transition-opacity">
                  <button 
                    v-if="getFileExtension(doc.filename) === 'pdf' && doc.minioUrl"
                    @click="openPdfPreview(doc.minioUrl, doc.filename)"
                    class="text-on-surface-variant hover:text-primary transition-colors p-xs rounded hover:bg-surface-variant cursor-pointer" 
                    title="预览 PDF"
                  >
                    <span class="material-symbols-outlined text-[18px]">menu_book</span>
                  </button>
                  <button 
                    @click="viewDetails(doc)"
                    class="text-on-surface-variant hover:text-primary transition-colors p-xs rounded hover:bg-surface-variant cursor-pointer" 
                    title="查看详情"
                  >
                    <span class="material-symbols-outlined text-[18px]">visibility</span>
                  </button>
                  <button 
                    @click="deleteDoc(doc)"
                    class="text-on-surface-variant hover:text-error transition-colors p-xs rounded hover:bg-error-container cursor-pointer" 
                    title="删除"
                    :disabled="deletingIds.has(doc.fileHash)"
                  >
                    <span v-if="deletingIds.has(doc.fileHash)" class="material-symbols-outlined text-[18px] animate-spin">sync</span>
                    <span v-else class="material-symbols-outlined text-[18px]">delete</span>
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <!-- Pagination -->
      <div class="border-t border-outline-variant p-sm flex items-center justify-between bg-surface-container">
        <span class="font-body-sm text-body-sm text-on-surface-variant">共 {{ totalCount }} 条记录</span>
        <div class="flex items-center gap-xs">
          <button 
            @click="prevPage"
            :disabled="currentPage === 1"
            class="p-xs text-outline-variant rounded hover:bg-surface-bright disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
          >
            <span class="material-symbols-outlined text-[18px]">chevron_left</span>
          </button>
          <span class="font-body-sm text-body-sm text-on-background px-sm">{{ currentPage }} / {{ totalPages || 1 }}</span>
          <button 
            @click="nextPage"
            :disabled="currentPage >= totalPages"
            class="p-xs text-outline-variant rounded hover:bg-surface-bright disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
          >
            <span class="material-symbols-outlined text-[18px]">chevron_right</span>
          </button>
        </div>
      </div>
    </div>

    <!-- Upload Modal Overlay -->
    <div 
      v-if="showUploadModal" 
      class="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-md"
    >
      <div @click.stop class="bg-surface-container-lowest border border-outline-variant rounded-xl w-full max-w-2xl shadow-xl flex flex-col max-h-[85vh] overflow-hidden">
        <header class="p-md border-b border-outline-variant flex justify-between items-center bg-surface-bright">
          <h3 class="font-headline-sm text-headline-sm text-on-background flex items-center gap-xs">
            <span class="material-symbols-outlined text-primary">upload_file</span>
            上传新文档到知识库
          </h3>
          <button 
            @click="closeUploadModal"
            class="text-on-surface-variant hover:bg-surface-container-high p-1 rounded-md transition-colors cursor-pointer"
          >
            <span class="material-symbols-outlined">close</span>
          </button>
        </header>

        <main class="flex-1 overflow-y-auto p-md space-y-md">
          <!-- Drag & Drop Zone -->
          <div 
            @dragover.prevent="dragOver = true"
            @dragleave.prevent="dragOver = false"
            @drop.prevent="handleDrop"
            class="border-2 border-dashed rounded-xl p-xl text-center cursor-pointer transition-all flex flex-col items-center justify-center space-y-xs"
            :class="[dragOver ? 'border-primary bg-primary-fixed/20' : 'border-outline-variant hover:border-primary']"
            @click="triggerFileSelect"
          >
            <input 
              ref="fileInput"
              type="file" 
              multiple
              class="hidden" 
              @change="handleFileSelect"
            />
            <span class="material-symbols-outlined text-primary text-[48px]" style="font-variation-settings: 'FILL' 0;">cloud_upload</span>
            <p class="font-body-md text-body-md font-semibold text-on-background">拖拽文件到这里，或 <span class="text-primary hover:underline">点击上传</span></p>
            <p class="font-label-md text-label-md text-outline">支持 PDF, Word, Markdown, TXT, CSV, MP4, MP3 等，最大支持 500MB</p>
          </div>

          <!-- Selected Files Progress List -->
          <div v-if="uploadQueue.length > 0" class="space-y-sm">
            <h4 class="font-label-md text-label-md text-on-surface-variant uppercase tracking-wider">上传队列 ({{ uploadQueue.length }})</h4>
            <div class="space-y-xs max-h-60 overflow-y-auto pr-1">
              <div 
                v-for="item in uploadQueue" 
                :key="item.id"
                class="border border-outline-variant rounded-lg p-sm bg-background flex flex-col gap-xs"
              >
                <div class="flex items-center justify-between gap-sm">
                  <div class="flex items-center gap-xs min-w-0">
                    <span class="material-symbols-outlined text-outline" style="font-variation-settings: 'FILL' 1;">
                      {{ getFileIcon(item.name) }}
                    </span>
                    <span class="font-medium text-body-sm truncate max-w-[280px]" :title="item.name">{{ item.name }}</span>
                    <span class="text-[11px] text-outline">({{ formatBytes(item.size) }})</span>
                  </div>
                  <span 
                    class="font-label-sm text-label-sm px-xs py-0.5 rounded font-semibold"
                    :class="getUploadStatusClasses(item.status)"
                  >
                    {{ item.statusText }}
                  </span>
                </div>
                <!-- Progress bar -->
                <div class="w-full bg-surface-container rounded-full h-1.5 overflow-hidden">
                  <div 
                    class="bg-primary h-full transition-all duration-300"
                    :style="{ width: item.progress + '%' }"
                  ></div>
                </div>
              </div>
            </div>
          </div>
        </main>

        <footer class="p-md border-t border-outline-variant bg-surface-bright flex justify-end gap-sm">
          <button 
            @click="closeUploadModal"
            class="px-md py-sm border border-outline-variant rounded-lg font-label-md text-label-md hover:bg-surface-container-high transition-colors cursor-pointer"
          >
            关闭
          </button>
          <button 
            @click="startUploadQueue"
            :disabled="!hasPendingUploads || uploading"
            class="px-md py-sm bg-primary text-on-primary rounded-lg font-label-md text-label-md hover:opacity-90 active:opacity-100 disabled:opacity-50 transition-all cursor-pointer shadow-sm"
          >
            {{ uploading ? '正在上传...' : '开始上传' }}
          </button>
        </footer>
      </div>
    </div>

    <!-- Document Detail Drawer -->
    <div 
      v-if="activeDocDetails" 
      class="fixed inset-0 bg-black/30 z-50 flex justify-end"
      @click="activeDocDetails = null"
    >
      <div 
        @click.stop
        class="bg-surface-container-lowest w-full max-w-lg shadow-xl h-full flex flex-col p-lg space-y-md border-l border-outline-variant animate-slide-in"
      >
        <header class="flex justify-between items-center border-b border-outline-variant pb-md">
          <h3 class="font-headline-sm text-headline-sm text-on-background flex items-center gap-xs">
            <span class="material-symbols-outlined text-primary">description</span>
            文档元数据详情
          </h3>
          <button 
            @click="activeDocDetails = null"
            class="text-on-surface-variant hover:bg-surface-container-high p-1 rounded-md transition-colors cursor-pointer"
          >
            <span class="material-symbols-outlined">close</span>
          </button>
        </header>

        <main class="flex-1 overflow-y-auto space-y-lg pr-1">
          <div class="space-y-sm">
            <div class="font-label-md text-label-md text-outline uppercase tracking-wider">文件基本信息</div>
            <div class="bg-background rounded-xl p-md border border-outline-variant space-y-sm">
              <div class="flex justify-between">
                <span class="text-on-surface-variant font-body-sm">文件名:</span>
                <span class="font-semibold text-on-background font-body-sm select-all">{{ activeDocDetails.filename }}</span>
              </div>
              <div class="flex justify-between">
                <span class="text-on-surface-variant font-body-sm">文件大小:</span>
                <span class="font-medium text-on-background font-body-sm">{{ formatBytes(activeDocDetails.size) }}</span>
              </div>
              <div class="flex justify-between">
                <span class="text-on-surface-variant font-body-sm">切片总数:</span>
                <span class="font-medium text-on-background font-body-sm">{{ activeDocDetails.chunksCount || 0 }}</span>
              </div>
              <div class="flex justify-between">
                <span class="text-on-surface-variant font-body-sm">更新时间:</span>
                <span class="font-medium text-on-background font-body-sm">{{ formatDateTime(activeDocDetails.createdAt) }}</span>
              </div>
            </div>
          </div>

          <div class="space-y-sm">
            <div class="font-label-md text-label-md text-outline uppercase tracking-wider">唯一哈希标识 (SHA-256)</div>
            <div class="bg-background rounded-xl p-md border border-outline-variant font-mono text-[12px] break-all select-all text-on-surface-variant leading-relaxed">
              {{ activeDocDetails.fileHash }}
            </div>
          </div>

          <div class="space-y-sm">
            <div class="font-label-md text-label-md text-outline uppercase tracking-wider">解析向量状态</div>
            <div class="bg-background rounded-xl p-md border border-outline-variant space-y-sm">
              <div class="flex justify-between items-center">
                <span class="text-on-surface-variant font-body-sm">解析状态:</span>
                <span 
                  class="font-semibold text-body-sm border rounded px-xs py-0.5"
                  :class="getStatusClasses(activeDocDetails.status)"
                >
                  {{ formatStatus(activeDocDetails.status) }}
                </span>
              </div>
              <div v-if="activeDocDetails.errorMessage" class="text-error font-body-sm mt-xs">
                <strong>错误日志:</strong> {{ activeDocDetails.errorMessage }}
              </div>
            </div>
          </div>
          
          <div v-if="getFileExtension(activeDocDetails.filename) === 'pdf' && activeDocDetails.minioUrl" class="space-y-sm">
            <button 
              @click="openPdfPreview(activeDocDetails.minioUrl, activeDocDetails.filename)"
              class="w-full flex items-center justify-center gap-xs bg-primary text-on-primary py-xs rounded-lg font-label-md text-label-md hover:opacity-90 active:opacity-100 transition-opacity cursor-pointer shadow-sm"
            >
              <span class="material-symbols-outlined text-[16px]">menu_book</span>
              在线预览 PDF 文档
            </button>
          </div>
        </main>
      </div>
    </div>

    <!-- PDF Preview Modal -->
    <div 
      v-if="previewPdfUrl" 
      class="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-md"
      @click="closePdfPreview"
    >
      <div @click.stop class="bg-surface-container-lowest border border-outline-variant rounded-xl w-full max-w-4xl shadow-xl flex flex-col h-[85vh] overflow-hidden animate-scale-in">
        <header class="p-md border-b border-outline-variant flex justify-between items-center bg-surface-bright">
          <h3 class="font-headline-sm text-headline-sm text-on-background flex items-center gap-xs truncate pr-lg">
            <span class="material-symbols-outlined text-primary">picture_as_pdf</span>
            文档在线预览: {{ previewPdfName }}
          </h3>
          <button 
            @click="closePdfPreview"
            class="text-on-surface-variant hover:bg-surface-container-high p-1 rounded-md transition-colors cursor-pointer flex-shrink-0"
          >
            <span class="material-symbols-outlined">close</span>
          </button>
        </header>
        <main class="flex-1 bg-surface-container-low p-sm flex items-center justify-center relative">
          <iframe 
            :src="previewPdfUrl" 
            class="w-full h-full border-none rounded-lg bg-white"
          ></iframe>
        </main>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useAuthStore } from '../store/auth'
import { documents, upload } from '../api'

const authStore = useAuthStore()

const searchKeyword = ref('')
const filterType = ref('')
const documentsList = ref([])
const totalCount = ref(0)
const currentPage = ref(1)
const totalPages = ref(1)
const loadingList = ref(false)

const showUploadModal = ref(false)
const dragOver = ref(false)
const fileInput = ref(null)
const uploadQueue = ref([])
const uploading = ref(false)

const activeDocDetails = ref(null)
const deletingIds = ref(new Set()) // hashes currently in deletion process
const activeTaskIds = ref(new Map()) // taskId -> fileHash currently polling delete status

const previewPdfUrl = ref('')
const previewPdfName = ref('')

const openPdfPreview = (url, filename) => {
  previewPdfUrl.value = url
  previewPdfName.value = filename
}

const closePdfPreview = () => {
  previewPdfUrl.value = ''
  previewPdfName.value = ''
}

let pollTimer = null

onMounted(() => {
  loadDocuments()
  // Start general status polling for list updates every 4 seconds
  pollTimer = setInterval(() => {
    pollDocumentStatuses()
  }, 4000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

const loadDocuments = async () => {
  loadingList.value = true
  try {
    const res = await documents.list({
      page: currentPage.value,
      pageSize: 10,
      userId: authStore.userId,
      sourceType: filterType.value || undefined,
      keyword: searchKeyword.value || undefined,
      sortBy: 'createdAt',
      sortOrder: 'DESC'
    })
    if (res.code === 200) {
      // Backend return schema: data: { records: [], total: ... }
      documentsList.value = res.data.records || []
      totalCount.value = res.data.total || 0
      totalPages.value = res.data.totalPages || 1
    }
  } catch (err) {
    console.error('获取文档列表失败:', err)
  } finally {
    loadingList.value = false
  }
}

// Background status poller to auto-refresh table rows that are PROCESSING/CHUNKING
const pollDocumentStatuses = async () => {
  // Check if any list item requires status update
  const hasProcessing = documentsList.value.some(doc => 
    isDocProcessing(doc.status)
  )
  
  if (hasProcessing || deletingIds.value?.size > 0 || activeTaskIds.value?.size > 0) {
    // silently fetch documents to refresh state
    try {
      const res = await documents.list({
        page: currentPage.value,
        pageSize: 10,
        userId: authStore.userId,
        sourceType: filterType.value || undefined,
        keyword: searchKeyword.value || undefined,
        sortBy: 'createdAt',
        sortOrder: 'DESC'
      })
      if (res.code === 200) {
        documentsList.value = res.data.records || []
      }

      // Check active delete tasks
      for (const [taskId, fileHash] of activeTaskIds.value.entries()) {
        const taskRes = await documents.getDeleteStatus(taskId)
        if (taskRes.code === 200) {
          const status = taskRes.data.status // e.g. SUCCESS / FAILED / PARTIAL / COMPLETED
          if (status === 'SUCCESS' || status === 'FAILED' || status === 'PARTIAL' || status === 'COMPLETED') {
            deletingIds.value.delete(fileHash)
            activeTaskIds.value.delete(taskId)
            loadDocuments()
          }
        }
      }
    } catch (err) {
      console.error('Polling refresh error:', err)
    }
  }
}

// Debounce search input
let searchTimeout
const debouncedSearch = () => {
  clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    currentPage.value = 1
    loadDocuments()
  }, 4000)
}

const nextPage = () => {
  if (currentPage.value < totalPages.value) {
    currentPage.value++
    loadDocuments()
  }
}

const prevPage = () => {
  if (currentPage.value > 1) {
    currentPage.value--
    loadDocuments()
  }
}

// Helpers
const getFileIcon = (filename) => {
  const ext = getFileExtension(filename)
  if (ext === 'pdf') return 'picture_as_pdf'
  if (['doc', 'docx'].includes(ext)) return 'description'
  if (['md', 'txt'].includes(ext)) return 'article'
  if (ext === 'csv') return 'table_view'
  if (['xls', 'xlsx'].includes(ext)) return 'analytics'
  if (['mp4', 'mkv', 'avi'].includes(ext)) return 'video_library'
  if (['mp3', 'wav'].includes(ext)) return 'audio_file'
  return 'draft'
}

const getFileExtension = (filename) => {
  return filename?.split('.').pop().toLowerCase() || ''
}

const formatBytes = (bytes, decimals = 2) => {
  if (bytes === 0) return '0 Bytes'
  const k = 1024
  const dm = decimals < 0 ? 0 : decimals
  const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i]
}

const formatDateTime = (timestamp) => {
  if (!timestamp) return '-'
  const date = new Date(timestamp)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

const isDocProcessing = (status) => {
  return status === 'UPLOADING' ||
         status === 'UPLOAD_SUCCESS' ||
         status === 'PROCESSING' ||
         status === 'CHUNKING' ||
         status === 'VECTORIZING' ||
         status === 'REINDEXING';
}

const formatStatus = (status) => {
  const map = {
    'UPLOADING': '上传中',
    'UPLOAD_SUCCESS': '排队中',
    'PROCESSING': '解析中',
    'CHUNKING': '切片中',
    'VECTORIZING': '向量化中',
    'REINDEXING': '重索引中',
    'SUCCESS': '成功',
    'FAILED': '失败'
  }
  return map[status] || status
}

const getStatusClasses = (status) => {
  if (status === 'SUCCESS') return 'bg-primary-fixed border-primary-fixed-dim text-primary'
  if (isDocProcessing(status)) return 'bg-secondary-fixed border-secondary-fixed-dim text-secondary'
  return 'bg-error-container border-error text-error'
}

const getUploadStatusClasses = (status) => {
  if (status === 'completed') return 'bg-primary-fixed text-primary'
  if (status === 'uploading') return 'bg-secondary-fixed text-secondary'
  if (status === 'failed') return 'bg-error-container text-error'
  return 'bg-surface-container text-outline'
}

const deleteDoc = async (doc) => {
  if (confirm(`确认要从知识库中删除文档 《${doc.filename}》 吗？`)) {
    try {
      deletingIds.value.add(doc.fileHash)
      const res = await documents.delete(doc.fileHash, authStore.userId)
      if (res.code === 200) {
        // Track the background delete task
        const taskId = res.data.taskId
        activeTaskIds.value.set(taskId, doc.fileHash)
        // Refresh local list row to show deleting loading state
        doc.status = 'PROCESSING'
      } else {
        deletingIds.value.delete(doc.fileHash)
        alert(res.message || '删除请求失败')
      }
    } catch (err) {
      deletingIds.value.delete(doc.fileHash)
      alert(err.message || '删除失败')
    }
  }
}

const viewDetails = (doc) => {
  activeDocDetails.value = doc
}

// Upload Operations
const closeUploadModal = () => {
  if (uploading.value) {
    if (!confirm('正在上传文件中，确定要关闭并取消未完成的任务吗？')) return
  }
  showUploadModal.value = false
  uploadQueue.value = []
  uploading.value = false
}

const triggerFileSelect = () => {
  fileInput.value.click()
}

const handleFileSelect = (e) => {
  addFilesToQueue(e.target.files)
}

const handleDrop = (e) => {
  dragOver.value = false
  addFilesToQueue(e.dataTransfer.files)
}

const addFilesToQueue = (files) => {
  for (let i = 0; i < files.length; i++) {
    const file = files[i]
    if (uploadQueue.value.some(item => item.name === file.name && item.size === file.size)) continue
    uploadQueue.value.push({
      id: Date.now() + '-' + i,
      file: file,
      name: file.name,
      size: file.size,
      progress: 0,
      status: 'pending', // pending, uploading, completed, failed
      statusText: '等待中'
    })
  }
}

const hasPendingUploads = computed(() => {
  return uploadQueue.value.some(item => item.status === 'pending' || item.status === 'failed')
})

// Calculate SHA-256 Hash natively
const calculateSHA256 = async (file) => {
  const arrayBuffer = await file.arrayBuffer()
  const hashBuffer = await crypto.subtle.digest('SHA-256', arrayBuffer)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
}

const startUploadQueue = async () => {
  uploading.value = true
  const pending = uploadQueue.value.filter(item => item.status === 'pending' || item.status === 'failed')
  
  for (const item of pending) {
    item.status = 'uploading'
    item.statusText = '计算哈希...'
    item.progress = 10

    try {
      const fileHash = await calculateSHA256(item.file)
      item.progress = 25
      
      // 1. Check if file already exists (秒传 check)
      item.statusText = '秒传检测...'
      const checkRes = await upload.check(fileHash, authStore.userId)
      
      if (checkRes.code === 200 && checkRes.data.exists) {
        item.progress = 100
        item.status = 'completed'
        item.statusText = '秒传成功'
        continue
      }

      // 2. Decide Upload Strategy: Direct upload for < 10MB, Chunk upload for larger files
      const tenMB = 10 * 1024 * 1024
      if (item.file.size < tenMB) {
        item.statusText = '直传中...'
        const upRes = await upload.upload(item.file, fileHash, authStore.userId)
        if (upRes.code === 200) {
          item.progress = 100
          item.status = 'completed'
          item.statusText = '上传成功'
        } else {
          throw new Error(upRes.message || '直传失败')
        }
      } else {
        // Chunk upload
        await handleChunkedUpload(item, fileHash)
      }
    } catch (err) {
      item.status = 'failed'
      item.statusText = '失败'
      console.error(err)
    }
  }

  uploading.value = false
  loadDocuments() // reload list
}

const uploadChunksWithLimit = async (fileHash, file, chunkSize, totalChunks, uploadedChunks, queueItem) => {
  const CONCURRENCY_LIMIT = 3
  const MAX_RETRIES = 3
  
  const chunksToUpload = []
  for (let i = 0; i < totalChunks; i++) {
    if (!uploadedChunks.includes(i)) {
      chunksToUpload.push(i)
    }
  }
  
  if (chunksToUpload.length === 0) return
  
  let activeUploads = 0
  let currentIndex = 0
  let hasFailed = false
  let failureError = null
  const uploadedCount = ref(uploadedChunks.length)
  
  return new Promise((resolve, reject) => {
    const next = async () => {
      if (hasFailed) return
      
      if (currentIndex >= chunksToUpload.length) {
        if (activeUploads === 0) {
          resolve()
        }
        return
      }
      
      const chunkIndex = chunksToUpload[currentIndex++]
      activeUploads++
      
      const start = chunkIndex * chunkSize
      const end = Math.min(file.size, start + chunkSize)
      const chunkBlob = file.slice(start, end)
      
      let attempt = 0
      let success = false
      
      while (attempt < MAX_RETRIES && !success && !hasFailed) {
        attempt++
        try {
          queueItem.statusText = `上传中 ${uploadedCount.value}/${totalChunks} 分片...`
          const chunkRes = await upload.uploadChunk(fileHash, chunkIndex, chunkBlob, authStore.userId)
          if (chunkRes.code === 200) {
            success = true
          } else {
            console.warn(`分片 ${chunkIndex} 尝试 ${attempt} 失败:`, chunkRes.message)
          }
        } catch (err) {
          console.error(`分片 ${chunkIndex} 尝试 ${attempt} 抛出错误:`, err)
        }
      }
      
      activeUploads--
      
      if (!success) {
        hasFailed = true
        failureError = new Error(`分片 ${chunkIndex + 1} 上传失败 (重试 ${MAX_RETRIES} 次后)`)
        reject(failureError)
        return
      }
      
      uploadedCount.value++
      const progressPercent = Math.min(95, 25 + Math.floor((uploadedCount.value / totalChunks) * 70))
      queueItem.progress = progressPercent
      queueItem.statusText = `上传中 ${uploadedCount.value}/${totalChunks} 分片...`
      
      // Start next task
      next()
    }
    
    // Start initial workers
    for (let i = 0; i < Math.min(CONCURRENCY_LIMIT, chunksToUpload.length); i++) {
      next()
    }
  })
}

const handleChunkedUpload = async (queueItem, fileHash) => {
  const file = queueItem.file
  const chunkSize = 5 * 1024 * 1024 // 5MB chunks
  const totalChunks = Math.ceil(file.size / chunkSize)
  
  queueItem.statusText = '分片初始化...'
  const checkRes = await upload.chunkCheck(fileHash, file.name, file.size, totalChunks, authStore.userId)
  
  if (checkRes.code === 200 && checkRes.message.includes('秒传成功')) {
    queueItem.progress = 100
    queueItem.status = 'completed'
    queueItem.statusText = '秒传成功'
    return
  }

  const uploadedChunks = checkRes.data?.uploadedChunks || [] // indices already uploaded
  const baseProgress = checkRes.data?.progress || 0
  queueItem.progress = Math.max(25, Math.floor(baseProgress))

  // Upload missing chunks concurrently with retry
  await uploadChunksWithLimit(fileHash, file, chunkSize, totalChunks, uploadedChunks, queueItem)

  // 3. Call Merge
  queueItem.statusText = '分片合并中...'
  const mergeRes = await upload.mergeChunks(fileHash, file.name, authStore.userId)
  if (mergeRes.code === 200 && mergeRes.data.success) {
    queueItem.progress = 100
    queueItem.status = 'completed'
    queueItem.statusText = '合并成功'
  } else {
    throw new Error(mergeRes.message || '合并失败')
  }
}
</script>
