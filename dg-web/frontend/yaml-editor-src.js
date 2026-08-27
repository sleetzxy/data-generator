import { EditorView, keymap, highlightActiveLine, lineNumbers, drawSelection } from '@codemirror/view';
import { EditorState } from '@codemirror/state';
import { yaml } from '@codemirror/lang-yaml';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { tags } from '@lezer/highlight';

const yamlHighlightStyle = HighlightStyle.define([
    { tag: tags.keyword, color: '#0550ae', fontWeight: '600' },
    { tag: tags.string, color: '#0a3069' },
    { tag: tags.number, color: '#0550ae' },
    { tag: tags.bool, color: '#0550ae' },
    { tag: tags.null, color: '#8250df' },
    { tag: tags.comment, color: '#6e7781', fontStyle: 'italic' },
    { tag: tags.propertyName, color: '#953800' },
    { tag: tags.variableName, color: '#116329' },
    { tag: tags.separator, color: '#57606a' },
    { tag: tags.punctuation, color: '#57606a' }
]);

let editorView = null;
let usingTextareaFallback = false;
let fallbackTextarea = null;

function activateTextareaFallback(parent, initialValue, readOnly) {
    usingTextareaFallback = true;
    editorView = null;
    parent.innerHTML = '';
    parent.classList.add('yaml-editor-fallback-host');

    const textarea = document.createElement('textarea');
    textarea.className = 'yaml-textarea-fallback';
    textarea.spellcheck = false;
    textarea.value = initialValue || '';
    textarea.readOnly = !!readOnly;
    parent.appendChild(textarea);
    fallbackTextarea = textarea;
}

export async function createYamlEditor(parent, initialValue, readOnly) {
    destroyYamlEditor();
    if (!parent) {
        return null;
    }

    try {
        usingTextareaFallback = false;
        fallbackTextarea = null;

        const extensions = [
            lineNumbers(),
            highlightActiveLine(),
            drawSelection(),
            history(),
            yaml(),
            syntaxHighlighting(yamlHighlightStyle, { fallback: true }),
            EditorView.lineWrapping,
            keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
            EditorView.theme({
                '&': {
                    fontSize: '13px',
                    height: '100%',
                    backgroundColor: '#fff'
                },
                '.cm-scroller': {
                    fontFamily: 'ui-monospace, "Cascadia Code", Consolas, monospace',
                    lineHeight: '1.5'
                },
                '.cm-content': {
                    minHeight: '360px'
                },
                '.cm-gutters': {
                    backgroundColor: '#f6f8fa',
                    borderRight: '1px solid #d0d7de'
                },
                '.cm-activeLine': {
                    backgroundColor: '#f6f8fa'
                },
                '&.cm-focused': {
                    outline: 'none'
                }
            }),
            EditorView.editable.of(!readOnly)
        ];

        const state = EditorState.create({
            doc: initialValue || '',
            extensions
        });

        editorView = new EditorView({
            state,
            parent
        });
        return editorView;
    } catch (err) {
        console.warn('CodeMirror 初始化失败，已降级为文本框编辑器', err);
        activateTextareaFallback(parent, initialValue, readOnly);
        return null;
    }
}

export function setYamlEditorValue(value) {
    if (usingTextareaFallback && fallbackTextarea) {
        fallbackTextarea.value = value || '';
        return;
    }
    if (!editorView) {
        return;
    }
    editorView.dispatch({
        changes: {
            from: 0,
            to: editorView.state.doc.length,
            insert: value || ''
        }
    });
}

export function getYamlEditorValue() {
    if (usingTextareaFallback && fallbackTextarea) {
        return fallbackTextarea.value;
    }
    return editorView ? editorView.state.doc.toString() : '';
}

export function destroyYamlEditor() {
    if (editorView) {
        editorView.destroy();
        editorView = null;
    }
    if (fallbackTextarea) {
        const parent = fallbackTextarea.parentElement;
        fallbackTextarea = null;
        if (parent) {
            parent.innerHTML = '';
            parent.classList.remove('yaml-editor-fallback-host');
        }
    }
    usingTextareaFallback = false;
}
