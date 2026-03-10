<script setup>
import { reactive, ref } from 'vue'
import axios from 'axios'
import { useAuthStore } from '@/stores/authStore'

const authStore = useAuthStore()

const form = reactive({
	username: '',
	password: '',
})

const loading = ref(false)
const message = ref('')
const messageType = ref('')

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost'

async function handleLogin() {
	if (!form.username || !form.password) {
		message.value = '请输入用户名和密码'
		messageType.value = 'error'
		return
	}

	loading.value = true
	message.value = ''

	try {
		const { data } = await axios.post(`${API_BASE_URL}/api/auth/login`, {
			username: form.username,
			password: form.password,
		})

		authStore.setAuth({
			token: data.token,
			username: data.username,
			userId: data.id,
		})

		message.value = '登录成功'
		messageType.value = 'success'
	} catch (error) {
		const backendMessage = error.response?.data?.message
		message.value = backendMessage || '登录失败，请稍后重试'
		messageType.value = 'error'
	} finally {
		loading.value = false
	}
}
</script>

<template>
	<main class="login-page">
		<section class="login-card">
			<h1>商品库存系统登录</h1>
			<p class="subtitle">请输入账号信息</p>

			<form class="login-form" @submit.prevent="handleLogin">
				<label for="username">用户名</label>
				<input
					id="username"
					v-model.trim="form.username"
					type="text"
					placeholder="请输入用户名"
					autocomplete="username"
				/>

				<label for="password">密码</label>
				<input
					id="password"
					v-model="form.password"
					type="password"
					placeholder="请输入密码"
					autocomplete="current-password"
				/>

				<button type="submit" :disabled="loading">
					{{ loading ? '登录中...' : '登录' }}
				</button>
			</form>

			<p v-if="message" class="message" :class="messageType">{{ message }}</p>

			<p class="switch-link">
				还没有账号？
				<RouterLink to="/register">去注册</RouterLink>
			</p>
		</section>
	</main>
</template>

<style scoped>
:global(body) {
	margin: 0;
	font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.login-page {
	min-height: 100vh;
	display: grid;
	place-items: center;
	padding: 24px;
	background: linear-gradient(135deg, #eef5ff 0%, #f9fbff 50%, #e8f2ff 100%);
}

.login-card {
	width: min(420px, 100%);
	background: #ffffff;
	border: 1px solid #dbe6ff;
	border-radius: 16px;
	padding: 28px;
	box-shadow: 0 10px 24px rgba(20, 60, 140, 0.12);
}

h1 {
	margin: 0;
	font-size: 26px;
	color: #17356b;
}

.subtitle {
	margin: 8px 0 20px;
	color: #55719f;
}

.login-form {
	display: grid;
	gap: 10px;
}

label {
	font-size: 14px;
	color: #17356b;
}

input {
	border: 1px solid #b7c7e8;
	border-radius: 10px;
	padding: 10px 12px;
	font-size: 15px;
}

input:focus {
	outline: 2px solid #8db3ff;
	border-color: #8db3ff;
}

button {
	margin-top: 8px;
	border: none;
	border-radius: 10px;
	padding: 11px 14px;
	font-size: 15px;
	font-weight: 600;
	background: #2f6fed;
	color: #ffffff;
	cursor: pointer;
}

button:disabled {
	cursor: not-allowed;
	opacity: 0.7;
}

.message {
	margin: 16px 0 0;
	font-size: 14px;
}

.message.success {
	color: #1d7a38;
}

.message.error {
	color: #b42929;
}

.switch-link {
	margin: 14px 0 0;
	font-size: 14px;
	color: #55719f;
}

.switch-link a {
	color: #2f6fed;
	text-decoration: none;
}

.switch-link a:hover {
	text-decoration: underline;
}
</style>
