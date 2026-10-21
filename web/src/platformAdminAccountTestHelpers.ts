import { nextTick } from 'vue'

export async function settleControls() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

export function formField(label: string): HTMLElement {
  const item = Array.from(document.querySelectorAll<HTMLElement>('.el-dialog .el-form-item'))
    .find(item => item.querySelector('.el-form-item__label')?.textContent?.trim() === label)
  if (!item) throw new Error(`Missing form field: ${label}`)
  return item
}

export function formInput(label: string): HTMLInputElement {
  const input = formField(label).querySelector<HTMLInputElement>('input')
  if (!input) throw new Error(`Missing input: ${label}`)
  return input
}

export function setFormInput(label: string, value: string) {
  const input = formInput(label)
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  input.dispatchEvent(new Event('change', { bubbles: true }))
  input.dispatchEvent(new FocusEvent('blur', { bubbles: true }))
}

export async function selectFormOption(label: string, optionText: string) {
  formField(label).querySelector<HTMLElement>('.el-select__wrapper')!.click()
  await settleControls()
  const option = Array.from(document.querySelectorAll<HTMLElement>('.el-select-dropdown__item'))
    .find(item => item.textContent?.trim() === optionText)
  if (!option) throw new Error(`Missing option: ${optionText}`)
  option.click()
  await settleControls()
}

export function dialogSubmit() {
  const submit = document.querySelector<HTMLButtonElement>('.el-dialog button[type="submit"]')
  if (!submit) throw new Error('Missing dialog submit')
  submit.click()
}

export function clickText(root: ParentNode, text: string) {
  const button = Array.from(root.querySelectorAll<HTMLButtonElement>('button'))
    .find(button => button.textContent?.trim() === text)
  if (!button) throw new Error(`Missing button: ${text}`)
  button.click()
}
