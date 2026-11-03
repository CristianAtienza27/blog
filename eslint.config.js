// @ts-check
const eslint = require('@eslint/js');
const { defineConfig } = require('eslint/config');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

module.exports = defineConfig([
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        {
          type: 'attribute',
          prefix: 'app',
          style: 'camelCase',
        },
      ],
      '@angular-eslint/component-selector': [
        'error',
        {
          // 'attribute' permite componentes sobre elementos nativos: <a app-button>.
          type: ['element', 'attribute'],
          prefix: 'app',
          style: 'kebab-case',
        },
      ],
    },
  },
  {
    // Capa de contenido: solo el adaptador JSON lee src/content.
    files: ['src/**/*.ts'],
    ignores: ['src/app/content/json/**'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/*.json'],
              message: 'Los JSON de contenido solo se leen desde el adaptador (content/json).',
            },
          ],
        },
      ],
      'no-restricted-syntax': [
        'error',
        {
          selector: 'ImportExpression[source.value=/\\.json$/]',
          message: 'Los JSON de contenido solo se leen desde el adaptador (content/json).',
        },
        {
          selector: 'ImportExpression > TemplateLiteral',
          message: 'Los imports dinámicos con plantilla solo se permiten en el adaptador.',
        },
      ],
    },
  },
  {
    // Fuera de la capa de contenido solo se usan los repositorios abstractos y los modelos.
    files: ['src/**/*.ts'],
    ignores: ['src/app/content/**', 'src/app/app.config.ts'],
    rules: {
      '@typescript-eslint/no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/content/json/*', '**/content/records/*', '**/content/testing/*'],
              message: 'Inyecta los repositorios de content/repositories, no el adaptador.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {},
  },
]);
