'use strict';
const message = document.getElementById('message');
const params = new URLSearchParams(location.search);
if (params.has('error')) {
  message.textContent = 'Неверный логин или пароль. Попробуйте ещё раз.';
  message.hidden = false;
} else if (params.has('logout')) {
  message.textContent = 'Вы вышли из системы.';
  message.className = 'success';
  message.hidden = false;
}
fetch('/login/csrf', {cache: 'no-store', signal: AbortSignal.timeout(5000)})
  .then(response => { if (!response.ok) throw new Error('csrf'); return response.json(); })
  .then(csrf => {
    const input = document.createElement('input');
    input.type = 'hidden'; input.name = csrf.parameterName; input.value = csrf.token;
    document.getElementById('login-form').append(input);
    document.getElementById('submit').disabled = false;
  })
  .catch(() => { message.textContent = 'Не удалось подготовить вход. Обновите страницу.'; message.className = ''; message.hidden = false; });
