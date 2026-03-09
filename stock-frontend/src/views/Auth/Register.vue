<script setup>
import { reactive, ref } from 'vue'
import axios from 'axios'
import { useRouter } from 'vue-router'

const router = useRouter()

const form = reactive({
	username: '',
	password: '',
	confirmPassword: '',
})

const loading = ref(false)
const message = ref('')
const messageType = ref('')
const confirmPasswordError = ref('')

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8089'

function validateConfirmPassword() {
  if (!form.confirmPassword) {
    confirmPasswordError.value = ''
    return
  }

  confirmPasswordError.value =
    form.password === form.confirmPassword ? '' : '两次输入的密码不一致'
}

async function handleRegister() {
	validateConfirmPassword()

	if (!form.username || !form.password || !form.confirmPassword) {
		message.value = '请完整填写注册信息'
		messageType.value = 'error'
		return
	}

	if (confirmPasswordError.value) {
		message.value = confirmPasswordError.value
		messageType.value = 'error'
		return
	}

	loading.value = true
	message.value = ''

	try {
		const { data } = await axios.post(`${API_BASE_URL}/api/auth/register`, {
			username: form.username,
			password: form.password,
		})

		message.value = data.message || '注册成功'
		messageType.value = 'success'
		form.password = ''
		form.confirmPassword = ''
		confirmPasswordError.value = ''

		setTimeout(() => {
			router.push('/login')
		}, 1000)
	} catch (error) {
		const backendMessage = error.response?.data?.message
		message.value = backendMessage || '注册失败，请稍后重试'
		messageType.value = 'error'
	} finally {
		loading.value = false
	}
}
</script>

<template>
	<main class="register-page">
		<section class="register-card">
			<h1>商品库存系统注册</h1>
			<p class="subtitle">创建一个新账号</p>

			<form class="register-form" @submit.prevent="handleRegister">
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
					@input="validateConfirmPassword"
					type="password"
					placeholder="请输入密码"
					autocomplete="new-password"
				/>

				<label for="confirmPassword">确认密码</label>
				<input
					id="confirmPassword"
					v-model="form.confirmPassword"
					@input="validateConfirmPassword"
					type="password"
					placeholder="请再次输入密码"
					autocomplete="new-password"
				/>
				<p v-if="confirmPasswordError" class="inline-error">{{ confirmPasswordError }}</p>

				<button type="submit" :disabled="loading">
					{{ loading ? '注册中...' : '注册' }}
				</button>
			</form>

			<p v-if="message" class="message" :class="messageType">{{ message }}</p>

			<p class="switch-link">
				已有账号？
				<RouterLink to="/login">去登录</RouterLink>
			</p>
		</section>
	</main>
</template>

<style scoped>
:global(body) {
	margin: 0;
	font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.register-page {
	min-height: 100vh;
	display: grid;
	place-items: center;
	padding: 24px;
	background: linear-gradient(135deg, #eef5ff 0%, #f9fbff 50%, #e8f2ff 100%);
}

.register-card {
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

.register-form {
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

.inline-error {
	margin: -4px 0 0;
	font-size: 13px;
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
