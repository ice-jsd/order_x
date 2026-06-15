<script setup lang="ts">
import ExecutionRecordsPanel from '../account/components/execution-records-panel.vue';

defineOptions({
  name: 'TicketExecutionRecords'
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <ExecutionRecordsPanel />
  </div>
</template>
