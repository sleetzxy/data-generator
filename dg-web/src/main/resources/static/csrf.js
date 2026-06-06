function getCsrfToken() {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
    return match ? decodeURIComponent(match[1]) : null;
}

function applyCsrfToForms() {
    document.querySelectorAll('form[method="post"], form[method="POST"]').forEach(form => {
        form.addEventListener('submit', () => {
            const token = getCsrfToken();
            if (!token) {
                return;
            }
            let input = form.querySelector('input[name="_csrf"]');
            if (!input) {
                input = document.createElement('input');
                input.type = 'hidden';
                input.name = '_csrf';
                form.appendChild(input);
            }
            input.value = token;
        });
    });
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', applyCsrfToForms);
} else {
    applyCsrfToForms();
}
