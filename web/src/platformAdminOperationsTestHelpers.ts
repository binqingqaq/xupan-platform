import { nextTick } from 'vue'

export async function settleOperations(ticks = 8) {
  for (let index = 0; index < ticks; index += 1) {
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
  }
}

export function operationButton(text: string, root: ParentNode = document): HTMLButtonElement {
  const button = [...root.querySelectorAll<HTMLButtonElement>('button')]
    .find(item => item.textContent?.trim() === text)
  if (!button) throw new Error(`Missing button: ${text}`)
  return button
}

export function operationField(label: string, root: ParentNode = document): HTMLInputElement | HTMLTextAreaElement {
  const item = [...root.querySelectorAll('.el-form-item')]
    .find(item => item.querySelector('.el-form-item__label')?.textContent?.trim() === label)
  const input = item?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input, textarea')
  if (!input) throw new Error(`Missing form field: ${label}`)
  return input
}

export async function fillOperationField(label: string, value: string, root: ParentNode = document) {
  const input = operationField(label, root)
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await settleOperations()
}
