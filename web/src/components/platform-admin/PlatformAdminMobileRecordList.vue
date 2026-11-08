<script setup lang="ts" generic="Row extends object">
interface MobileRecordField<T> {
  key: string
  label: string
  value: (row: T) => unknown
  spanAll?: boolean
}

withDefaults(defineProps<{
  rows: Row[]
  fields: MobileRecordField<Row>[]
  rowKey: (row: Row, index: number) => string | number
  loading?: boolean
  emptyText?: string
  detailsLabel?: string
}>(), {
  loading: false,
  emptyText: '暂无数据',
  detailsLabel: '查看详情',
})
const emit = defineEmits<{ detailsToggle: [row: Row, open: boolean] }>()

function displayValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '--'
  return String(value)
}

function onDetailsToggle(row: Row, event: Event) {
  emit('detailsToggle', row, (event.currentTarget as HTMLDetailsElement).open)
}
</script>

<template>
  <section class="platform-admin-mobile-record-list" v-loading="loading" aria-label="记录列表">
    <div v-if="loading && rows.length === 0" class="platform-admin-mobile-record-skeletons" aria-hidden="true">
      <article v-for="index in 3" :key="index" class="platform-admin-mobile-record-skeleton">
        <span />
        <span />
        <span />
      </article>
    </div>
    <el-empty v-else-if="rows.length === 0" :description="emptyText" />
    <article v-for="(row, index) in rows" v-else :key="rowKey(row, index)" class="platform-admin-mobile-record">
      <header v-if="$slots.heading" class="platform-admin-mobile-record-heading">
        <slot name="heading" :row="row" />
      </header>
      <dl class="platform-admin-mobile-record-fields">
        <div v-for="field in fields" :key="field.key" :class="{ 'is-span-all': field.spanAll }">
          <dt>{{ field.label }}</dt>
          <dd>
            <slot :name="`field-${field.key}`" :row="row" :value="field.value(row)">
              {{ displayValue(field.value(row)) }}
            </slot>
          </dd>
        </div>
      </dl>
      <details v-if="$slots.details" class="platform-admin-mobile-record-details" @toggle="onDetailsToggle(row, $event)">
        <summary>{{ detailsLabel }}</summary>
        <slot name="details" :row="row" />
      </details>
      <footer v-if="$slots.actions" class="platform-admin-mobile-record-actions">
        <slot name="actions" :row="row" />
      </footer>
    </article>
  </section>
</template>
