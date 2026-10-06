import js from '@eslint/js';
import globals from 'globals';
import hooks from 'eslint-plugin-react-hooks';

export default [
    {ignores: ['dist/**', 'node_modules/**']},
    {
        files: ['src/**/*.{js,jsx}', '*.js'],
        languageOptions: {
            ecmaVersion: 'latest',
            sourceType: 'module',
            parserOptions: {ecmaFeatures: {jsx: true}},
            globals: {...globals.browser, ...globals.node},
        },
        plugins: {'react-hooks': hooks},
        rules: {
            'no-undef': js.configs.recommended.rules['no-undef'],
            'react-hooks/rules-of-hooks': 'error',
            'react-hooks/exhaustive-deps': 'error',
        },
    },
];
