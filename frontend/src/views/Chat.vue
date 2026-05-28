<template>
  <div class="h-full flex overflow-hidden bg-background">
    <!-- Secondary Panel: Conversations History Sidebar -->
    <aside class="w-64 bg-surface-container-lowest border-r border-outline-variant h-full flex flex-col p-md flex-shrink-0 hidden lg:flex">
      <div class="font-label-md text-label-md text-outline uppercase tracking-wider mb-sm px-2">最近会话</div>
      <div class="flex-1 overflow-y-auto scrollbar-hide flex flex-col gap-xs pr-1">
        <div v-if="sessionsList.length === 0" class="text-center py-lg text-outline-variant text-body-sm">
          暂无历史会话
        </div>
        <div 
          v-for="session in sessionsList" 
          :key="session"
          @click="selectSession(session)"
          class="flex items-center justify-between py-2 px-3 rounded-md cursor-pointer transition-colors group text-left"
          :class="[currentSessionId === session ? 'bg-surface-container text-primary font-semibold' : 'hover:bg-surface-container-low text-on-surface-variant']"
        >
          <span class="font-body-sm text-body-sm truncate flex-1 pr-2">{{ formatSessionTitle(session) }}</span>
          <button 
            @click.stop="confirmDeleteSession(session)"
            class="text-outline-variant hover:text-error opacity-0 group-hover:opacity-100 transition-opacity cursor-pointer flex items-center justify-center p-xs rounded hover:bg-error-container"
            title="删除会话"
          >
            <span class="material-symbols-outlined text-[16px]">delete</span>
          </button>
        </div>
      </div>
    </aside>

    <!-- Main Chat Feed Area -->
    <div class="flex-1 flex flex-col min-w-0 bg-background relative h-full">
      <!-- Chat Message Window -->
      <div 
        ref="messageContainer"
        class="flex-grow overflow-y-auto p-md md:p-lg flex flex-col gap-lg pb-36 scrollbar-hide"
      >
        <div v-if="chatMessages.length === 0" class="flex flex-col items-center justify-center h-full max-w-lg mx-auto text-center space-y-md my-auto opacity-75">
          <span class="material-symbols-outlined text-primary text-[64px]" style="font-variation-settings: 'FILL' 1;">chat_bubble_outline</span>
          <h3 class="font-headline-md text-headline-md text-on-background">智能 AI 助手</h3>
          <p class="font-body-sm text-body-sm text-on-surface-variant">
            基于大语言模型与多路径知识库召回的 RAG 问答平台。您可以输入任何关于知识库文档的问题。
          </p>
        </div>

        <div 
          v-for="(msg, index) in chatMessages" 
          :key="index"
          class="flex w-full"
          :class="[msg.role === 'user' ? 'justify-end' : 'justify-start']"
        >
          <div 
            class="max-w-[85%] md:max-w-[75%] flex gap-sm"
            :class="[msg.role === 'user' ? 'flex-row-reverse' : 'flex-row']"
          >
            <!-- Avatar -->
            <div 
              class="w-8 h-8 rounded-full border border-outline-variant flex-shrink-0 flex items-center justify-center font-bold text-xs"
              :class="[msg.role === 'user' ? 'bg-primary-container text-on-primary' : 'bg-surface-container-lowest text-primary']"
            >
              {{ msg.role === 'user' ? authStore.username[0].toUpperCase() : 'AI' }}
            </div>
            
            <!-- Message Bubble -->
            <div 
              class="p-md rounded-lg shadow-sm font-body-md text-body-md whitespace-pre-wrap leading-relaxed"
              :class="[
                msg.role === 'user' 
                  ? 'bg-primary-container text-on-primary rounded-tr-none' 
                  : 'bg-surface-container-lowest border border-outline-variant border-l-4 border-l-primary-container text-on-surface rounded-tl-none'
              ]"
            >
              <div v-html="formatMessageContent(msg.content)"></div>
            </div>
          </div>
        </div>

        <!-- Typing Loader -->
        <div v-if="streaming" class="flex w-full justify-start">
          <div class="max-w-[85%] md:max-w-[75%] flex gap-sm">
            <div class="w-8 h-8 rounded-full border border-outline-variant flex-shrink-0 flex items-center justify-center font-bold text-xs bg-surface-container-lowest text-primary">
              AI
            </div>
            <div class="bg-surface-container-lowest border border-outline-variant border-l-4 border-l-primary-container p-md rounded-lg rounded-tl-none shadow-sm flex items-center gap-sm">
              <span class="typing-indicator flex items-center text-primary">
                <span class="w-2 h-2 bg-primary rounded-full mx-0.5"></span>
                <span class="w-2 h-2 bg-primary rounded-full mx-0.5"></span>
                <span class="w-2 h-2 bg-primary rounded-full mx-0.5"></span>
              </span>
              <span class="font-body-sm text-body-sm text-outline">大模型生成中...</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Input composer container -->
      <div class="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-background via-background to-transparent pt-lg pb-md px-md md:px-lg">
        <div class="max-w-4xl mx-auto bg-surface-container-lowest border border-outline-variant rounded-xl shadow-md p-2 flex flex-col transition-all focus-within:border-primary focus-within:shadow-lg">
          <textarea 
            v-model="inputMessage" 
            @keydown.enter.prevent="sendMessage"
            class="w-full bg-transparent border-none focus:ring-0 resize-none font-body-md text-body-md p-3 text-on-surface placeholder:text-outline h-20 max-h-48 outline-none" 
            placeholder="请输入您的问题，按回车发送..."
            :disabled="streaming"
          ></textarea>
          <div class="flex items-center justify-between mt-2 px-2 pb-1">
            <div class="flex items-center gap-sm">
              <!-- Mode Selection (Optional UI enhancement) -->
              <span class="font-label-md text-label-md text-outline bg-surface-container px-2 py-1 rounded-md flex items-center gap-xs">
                <span class="material-symbols-outlined text-[14px]">source</span>
                混合检索召唤 Rerank 激活
              </span>
            </div>
            <div class="flex items-center gap-xs">
              <!-- Recording status text indicator -->
              <span v-if="recording" class="text-error font-body-sm animate-pulse mr-xs flex items-center gap-2">
                <span class="w-2 h-2 bg-error rounded-full animate-ping"></span>
                正在录音...
              </span>
              <!-- Mic Button -->
              <button 
                @click="toggleRecording"
                :disabled="streaming"
                class="p-2 rounded-lg hover:bg-surface-container-high transition-colors flex items-center justify-center cursor-pointer shadow-sm"
                :class="[recording ? 'bg-error-container text-error hover:bg-error-container-high' : 'bg-surface-container text-primary']"
                :title="recording ? '结束录音并识别' : '语音对话'"
              >
                <span class="material-symbols-outlined text-[20px]">
                  {{ recording ? 'mic_off' : 'mic' }}
                </span>
              </button>
              <button 
                @click="sendMessage"
                :disabled="streaming || !inputMessage.trim()"
                class="bg-primary-container text-on-primary p-2 rounded-lg hover:opacity-90 active:opacity-100 transition-opacity flex items-center justify-center disabled:opacity-50 shadow-sm cursor-pointer" 
                title="发送消息"
              >
                <span class="material-symbols-outlined text-[20px]" style="font-variation-settings: 'FILL' 1;">send</span>
              </button>
            </div>
          </div>
        </div>
        <p class="text-center font-label-md text-label-md text-outline mt-sm">大模型可能犯错。重要信息请通过引用来源进行复核。</p>
      </div>
    </div>

    <!-- Right Panel: Citations / References Panel -->
    <aside 
      v-if="citationsList.length > 0"
      class="hidden xl:flex flex-col w-80 bg-surface-container-lowest border-l border-outline-variant flex-shrink-0 h-full overflow-hidden shadow-sm"
    >
      <div class="p-md border-b border-outline-variant flex items-center justify-between bg-surface-bright">
        <h3 class="font-headline-sm text-[18px] leading-[24px] font-semibold text-on-surface flex items-center gap-sm">
          <span class="material-symbols-outlined text-primary" style="font-variation-settings: 'FILL' 1;">source</span>
          知识来源 ({{ citationsList.length }})
        </h3>
        <button 
          @click="clearCitations"
          class="text-on-surface-variant hover:bg-surface-container-high p-1 rounded-md transition-colors cursor-pointer"
        >
          <span class="material-symbols-outlined text-[20px]">close</span>
        </button>
      </div>
      <div class="flex-1 overflow-y-auto p-md flex flex-col gap-md bg-background/50">
        <div 
          v-for="(cite, i) in citationsList" 
          :key="i"
          @click="openPdfFromCitation(cite)"
          class="bg-surface-container-lowest border border-outline-variant rounded-lg p-md shadow-sm hover:border-primary transition-all duration-200 cursor-pointer"
        >
          <div class="flex items-start justify-between mb-sm gap-xs">
            <div class="flex items-center gap-xs text-primary font-label-md text-label-md min-w-0">
              <span class="material-symbols-outlined text-[16px] flex-shrink-0">description</span>
              <span class="truncate font-semibold">{{ cite.sourceName }}</span>
            </div>
            <span class="bg-surface-container text-primary px-2 py-0.5 rounded-full font-label-md text-label-md flex-shrink-0">
              {{ cite.label }}
            </span>
          </div>
          <p
            v-if="formatCitationPath(cite)"
            class="font-label-md text-label-md text-outline mb-sm break-words"
          >
            {{ formatCitationPath(cite) }}
          </p>
          <p class="font-body-sm text-body-sm text-on-surface-variant line-clamp-4 leading-relaxed">
            {{ cite.text }}
          </p>
          <div class="mt-sm pt-sm border-t border-surface-variant flex items-center justify-between text-outline">
            <span class="font-label-md text-label-md flex items-center gap-xs">
              <span class="material-symbols-outlined text-[14px]">check_circle</span>
              匹配度: {{ cite.score }}%
            </span>
            <span class="font-label-md text-label-md text-primary hover:underline">查看源文本</span>
          </div>
        </div>
      </div>
    </aside>
    
    <!-- PDF Preview Modal -->
    <div 
      v-if="previewPdfUrl" 
      class="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-md"
      @click="previewPdfUrl = ''"
    >
      <div @click.stop class="bg-surface-container-lowest border border-outline-variant rounded-xl w-full max-w-4xl shadow-xl flex flex-col h-[85vh] overflow-hidden animate-scale-in">
        <header class="p-md border-b border-outline-variant flex justify-between items-center bg-surface-bright">
          <h3 class="font-headline-sm text-headline-sm text-on-background flex items-center gap-xs truncate pr-lg">
            <span class="material-symbols-outlined text-primary">picture_as_pdf</span>
            文档在线预览: {{ previewPdfName }}
          </h3>
          <button 
            @click="previewPdfUrl = ''"
            class="text-on-surface-variant hover:bg-surface-container-high p-1 rounded-md transition-colors cursor-pointer flex-shrink-0"
          >
            <span class="material-symbols-outlined">close</span>
          </button>
        </header>
        <main class="flex-1 bg-surface-container-low p-sm flex items-center justify-center">
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
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useAuthStore } from '../store/auth'
import { ai } from '../api'

const props = defineProps({
  newSessionTrigger: {
    type: Number,
    default: 0
  }
})

const emit = defineEmits(['trigger-new-session'])

const authStore = useAuthStore()

const sessionsList = ref([])
const currentSessionId = ref('')
const chatMessages = ref([])
const citationsList = ref([])
const inputMessage = ref('')
const streaming = ref(false)
const messageContainer = ref(null)
const hasNewTurns = ref(false)

const recording = ref(false)
let audioContext = null
let mediaStream = null
let processorNode = null
let sourceNode = null
let audioBuffers = []

const previewPdfUrl = ref('')
const previewPdfName = ref('')

const toggleRecording = async () => {
  if (recording.value) {
    await stopAndSendRecording()
  } else {
    await startRecording()
  }
}

const startRecording = async () => {
  try {
    audioBuffers = []
    mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    audioContext = new (window.AudioContext || window.webkitAudioContext)({
      sampleRate: 16000
    })
    
    sourceNode = audioContext.createMediaStreamSource(mediaStream)
    processorNode = audioContext.createScriptProcessor(4096, 1, 1)
    
    processorNode.onaudioprocess = (e) => {
      const inputData = e.inputBuffer.getChannelData(0)
      audioBuffers.push(new Float32Array(inputData))
    }
    
    sourceNode.connect(processorNode)
    processorNode.connect(audioContext.destination)
    recording.value = true
  } catch (err) {
    console.error('无法启动录音:', err)
    alert('启动麦克风录音失败，请确保已授予麦克风权限！')
  }
}

const downsampleBuffer = (buffer, inputSampleRate, outputSampleRate = 16000) => {
  if (inputSampleRate === outputSampleRate) {
    return buffer
  }
  if (inputSampleRate < outputSampleRate) {
    throw new Error('输入采样率过低')
  }
  const sampleRateRatio = inputSampleRate / outputSampleRate
  const newLength = Math.round(buffer.length / sampleRateRatio)
  const result = new Float32Array(newLength)
  let offsetResult = 0
  let offsetBuffer = 0
  while (offsetResult < result.length) {
    const nextOffsetBuffer = Math.round((offsetResult + 1) * sampleRateRatio)
    let accum = 0, count = 0
    for (let i = offsetBuffer; i < nextOffsetBuffer && i < buffer.length; i++) {
      accum += buffer[i]
      count++
    }
    result[offsetResult] = accum / count
    offsetResult++
    offsetBuffer = nextOffsetBuffer
  }
  return result
}

const stopAndSendRecording = async () => {
  if (!recording.value) return
  recording.value = false
  
  try {
    if (processorNode) {
      processorNode.disconnect()
      sourceNode.disconnect()
      processorNode = null
      sourceNode = null
    }
    if (mediaStream) {
      mediaStream.getTracks().forEach(track => track.stop())
      mediaStream = null
    }
    const inputSampleRate = audioContext.sampleRate
    if (audioContext) {
      await audioContext.close()
      audioContext = null
    }
    
    // Merge Float32Array buffers
    const totalLength = audioBuffers.reduce((acc, buf) => acc + buf.length, 0)
    if (totalLength === 0) {
      alert('录音为空，请再试一次！')
      return
    }
    
    const mergedBuffer = new Float32Array(totalLength)
    let offset = 0
    for (const buf of audioBuffers) {
      mergedBuffer.set(buf, offset)
      offset += buf.length
    }
    
    // Downsample
    const downsampled = downsampleBuffer(mergedBuffer, inputSampleRate, 16000)
    
    // Convert to Int16 PCM
    const pcmBuffer = new Int16Array(downsampled.length)
    for (let i = 0; i < downsampled.length; i++) {
      const s = Math.max(-1, Math.min(1, downsampled[i]))
      pcmBuffer[i] = s < 0 ? s * 0x8000 : s * 0x7FFF
    }
    
    const audioBlob = new Blob([pcmBuffer.buffer], { type: 'audio/pcm' })
    
    // Send to ASR backend
    inputMessage.value = '正在识别语音中...'
    const res = await ai.asr(audioBlob)
    if (res.code === 200 && res.data) {
      inputMessage.value = res.data
    } else {
      inputMessage.value = ''
      alert(res.message || '语音识别失败，请检查密钥配置')
    }
  } catch (err) {
    console.error('处理录音失败:', err)
    inputMessage.value = ''
    alert('录音处理或识别失败：' + err.message)
  }
}

const openPdfFromCitation = (cite) => {
  if (cite.minioUrl) {
    const ext = cite.sourceName?.split('.').pop().toLowerCase() || ''
    if (ext === 'pdf') {
      previewPdfUrl.value = cite.minioUrl
      previewPdfName.value = cite.sourceName
    } else {
      alert(`文档 《${cite.sourceName}》 不是 PDF 格式，暂不支持在线预览，您可以到文档管理下载。`)
    }
  } else {
    alert('该演示引文不支持在线预览。')
  }
}

// Watch layout-triggered event for new session
watch(() => props.newSessionTrigger, () => {
  initiateNewSession()
})

const handleBeforeUnload = () => {
  if (currentSessionId.value && hasNewTurns.value) {
    const baseURL = import.meta.env.VITE_API_BASE_URL || ''
    const data = JSON.stringify({
      sessionId: currentSessionId.value,
      userId: authStore.userId
    })
    fetch(`${baseURL}/ai/session/extract-profile`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'satoken': authStore.token
      },
      body: data,
      keepalive: true
    }).catch(() => {})
  }
}

onMounted(() => {
  loadSessions()
  window.addEventListener('beforeunload', handleBeforeUnload)
})

onUnmounted(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
  if (currentSessionId.value && hasNewTurns.value) {
    ai.extractProfile(currentSessionId.value, authStore.userId).catch(() => {})
  }
})

const loadSessions = async () => {
  try {
    const res = await ai.listSessions(authStore.userId)
    if (res.code === 200) {
      sessionsList.value = res.data.sessions || []
      // Select the first session by default if available
      if (sessionsList.value.length > 0) {
        selectSession(sessionsList.value[0])
      } else {
        initiateNewSession()
      }
    }
  } catch (err) {
    console.error('获取会话列表失败:', err)
  }
}

const initiateNewSession = async () => {
  try {
    const res = await ai.createSession(authStore.userId)
    if (res.code === 200) {
      const newSession = res.data.sessionId
      sessionsList.value.unshift(newSession)
      currentSessionId.value = newSession
      chatMessages.value = []
      citationsList.value = []
    }
  } catch (err) {
    console.error('创建新会话失败:', err)
  }
}

const selectSession = async (sessionId) => {
  if (currentSessionId.value && currentSessionId.value !== sessionId && hasNewTurns.value) {
    try {
      await ai.extractProfile(currentSessionId.value, authStore.userId)
    } catch (err) {
      console.error('切换会话时画像提炼失败:', err)
    }
  }

  currentSessionId.value = sessionId
  chatMessages.value = []
  citationsList.value = []
  hasNewTurns.value = false
  try {
    const res = await ai.getHistory(sessionId)
    if (res.code === 200) {
      chatMessages.value = res.data.map(m => ({
        role: m.role, // 'user' or 'ai'
        content: m.content
      }))
      scrollToBottom()
    }
  } catch (err) {
    console.error('获取历史记录失败:', err)
  }
}

const confirmDeleteSession = async (sessionId) => {
  if (confirm('确认要删除此会话吗？删除后会话历史不可恢复。')) {
    try {
      const res = await ai.deleteSession(sessionId, authStore.userId)
      if (res.code === 200) {
        sessionsList.value = sessionsList.value.filter(s => s !== sessionId)
        if (currentSessionId.value === sessionId) {
          if (sessionsList.value.length > 0) {
            selectSession(sessionsList.value[0])
          } else {
            initiateNewSession()
          }
        }
      }
    } catch (err) {
      console.error('删除会话失败:', err)
    }
  }
}

const sendMessage = async () => {
  if (!inputMessage.value.trim() || streaming.value) return

  const userQuery = inputMessage.value.trim()
  chatMessages.value.push({ role: 'user', content: userQuery })
  inputMessage.value = ''
  scrollToBottom()

  streaming.value = true
  
  // Create placeholder message for AI stream response
  const aiMessageIndex = chatMessages.value.push({ role: 'ai', content: '' }) - 1

  try {
    const baseURL = import.meta.env.VITE_API_BASE_URL || ''
    const response = await fetch(`${baseURL}/ai/multi-turn/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'satoken': authStore.token
      },
      body: JSON.stringify({
        userId: authStore.userId,
        sessionId: currentSessionId.value,
        turnCount: chatMessages.value.length - 1,
        message: userQuery
      })
    })

    if (!response.ok) {
      throw new Error('对话流请求失败')
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let done = false
    let buffer = ''

    while (!done) {
      const { value, done: readerDone } = await reader.read()
      done = readerDone
      if (value) {
        buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n').replace(/\r/g, '\n')
        const messages = buffer.split('\n\n')
        buffer = messages.pop() || ''

        for (const msg of messages) {
          handleSseMessage(msg, aiMessageIndex)
        }
        scrollToBottom()
      }
    }

    if (buffer) {
      handleSseMessage(buffer, aiMessageIndex)
      scrollToBottom()
    }

    hasNewTurns.value = true

  } catch (err) {
    console.error('Streaming error:', err)
    chatMessages.value[aiMessageIndex].content = '对不起，系统发生了异常，未能成功回答您的问题。'
  } finally {
    streaming.value = false
    scrollToBottom()
  }
}

const clearCitations = () => {
  citationsList.value = []
}

const parseSseMessage = (message) => {
  if (!message) return null

  let eventType = 'message'
  const dataLines = []

  for (const line of message.split('\n')) {
    if (!line) continue
    if (line.startsWith('event:')) {
      eventType = line.slice(6).trim()
      continue
    }
    if (line.startsWith('data:')) {
      let value = line.slice(5)
      if (value.startsWith(' ')) {
        value = value.slice(1)
      }
      dataLines.push(value)
    }
  }

  return {
    eventType,
    dataContent: dataLines.join('\n')
  }
}

const handleSseMessage = (message, aiMessageIndex) => {
  const parsed = parseSseMessage(message)
  if (!parsed) return

  if (parsed.eventType === 'citations') {
    try {
      citationsList.value = parsed.dataContent ? JSON.parse(parsed.dataContent) : []
    } catch (e) {
      console.error('解析引文失败:', e)
    }
    return
  }

  if (parsed.eventType === 'message') {
    chatMessages.value[aiMessageIndex].content += parsed.dataContent
  }
}

const formatCitationPath = (cite) => {
  const segments = []
  const docName = cite.docTitle || cite.sourceName
  if (docName) segments.push(docName)
  if (cite.sectionTitle) segments.push(cite.sectionTitle)
  if (cite.chunkIndex) segments.push(`分段 ${cite.chunkIndex}`)
  return segments.join(' > ')
}

const formatSessionTitle = (sessionId) => {
  // Return readable title for sessions
  if (sessionId.length > 10) {
    return `会话: ${sessionId.substring(0, 8)}...`
  }
  return `会话: ${sessionId}`
}

const formatMessageContent = (content) => {
  if (!content) return ''
  // Render bold markdown
  let formatted = content
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.*?)\*/g, '<em>$1</em>')
    .replace(/`([^`]+)`/g, '<code class="bg-surface-container text-primary px-1 rounded font-mono text-sm">$1</code>')
  
  // Render source tag pills
  formatted = formatted.replace(/(Page \d+|Section \d+|Chapter \d+)/g, 
    '<span class="inline-flex items-center gap-xs bg-surface-container text-primary px-2 py-0.5 rounded-full font-label-md text-label-md ml-1 cursor-pointer hover:opacity-80">$1</span>')

  return formatted
}

const scrollToBottom = () => {
  nextTick(() => {
    if (messageContainer.value) {
      messageContainer.value.scrollTop = messageContainer.value.scrollHeight
    }
  })
}
</script>
