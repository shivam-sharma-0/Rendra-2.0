import {randomUUID} from 'node:crypto';

/** Executed in Chromium, with data-only arguments. No model script/selector is evaluated. */
export function outcomeDom(definition, phase, fixture) {
  const visible = element => {
    if (!element || ![...element.getClientRects()].some(rect => rect.width > 0 && rect.height > 0)
        || element.closest('[hidden],[aria-hidden="true"]')) return false;
    for (let parent=element; parent; parent=parent.parentElement) {
      const style=getComputedStyle(parent);
      if (style.visibility !== 'visible' || Number(style.opacity) === 0) return false;
    }
    return true;
  };
  const roots = [...document.querySelectorAll(`[data-rendra-feature="${definition.id}"]`)].filter(visible);
  if (roots.length !== 1) {
    if (phase === 'ready' && roots.length === 0) {
      const links = [...document.querySelectorAll(`a[data-rendra-feature-link="${definition.id}"]`)].filter(visible);
      if (links.length === 1) {
        const target = new URL(links[0].href, location.href);
        if (target.origin === location.origin && ['http:','https:'].includes(target.protocol)) links[0].click();
      }
    }
    return {ok:false, reason:roots.length === 0 ? 'FEATURE_ROOT_MISSING' : 'FEATURE_ROOT_AMBIGUOUS'};
  }
  const root = roots[0];
  const forms = root.tagName === 'FORM' ? [root] : [...root.querySelectorAll('form')].filter(visible);
  if (forms.length !== 1) return {ok:false, reason:forms.length === 0 ? 'NATIVE_FORM_MISSING' : 'NATIVE_FORM_AMBIGUOUS'};
  const form = forms[0];
  const statuses = () => [...root.querySelectorAll('[role="status"][data-state="success"]')]
    .filter(visible).map(element => element.innerText.trim()).filter(Boolean);
  const items = token => [...root.querySelectorAll('li,[role="listitem"]')]
    .filter(element => visible(element) && element.innerText.includes(token))
    .filter(element => ![...element.querySelectorAll('li,[role="listitem"]')].some(child => visible(child) && child.innerText.includes(token)));
  const setValue = (control, value) => {
    const prototype = control.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype
      : control.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
    if (!setter) return false;
    setter.call(control, value);
    control.dispatchEvent(new Event('input', {bubbles:true}));
    control.dispatchEvent(new Event('change', {bubbles:true}));
    return true;
  };
  if (phase === 'ready') return {ok:true};
  if (phase === 'prepare') {
    const baseline = JSON.stringify(statuses());
    const controls = [...form.elements].filter(control => visible(control) && !control.disabled && !control.readOnly);
    const field = definition.field ? controls.filter(control => control.name === definition.field) : [];
    if (definition.kind === 'BROWSER_CRUD') {
      if (field.length !== 1) return {ok:false, reason:field.length === 0 ? 'FIELD_MISSING' : 'FIELD_AMBIGUOUS'};
      if (!['text','search','textarea'].includes(field[0].type)) return {ok:false, reason:'FIELD_NOT_EDITABLE'};
    }
    for (const control of controls) {
      if (['submit','reset','button','hidden'].includes(control.type)) continue;
      if (['checkbox','radio'].includes(control.type)) { if (control.required && !control.checked) control.click(); continue; }
      if (control.type === 'file') { if (control.required) return {ok:false}; continue; }
      let value = control === field[0] ? fixture.key : `${fixture.key}-${control.name || 'value'}`;
      if (control.tagName === 'SELECT') {
        value = [...control.options].find(option => !option.disabled && option.value)?.value;
        if (value === undefined) return {ok:false};
      } else if (control.type === 'email') value = `${fixture.key}@example.invalid`;
      else if (control.type === 'url') value = `https://example.invalid/${fixture.key}`;
      else if (control.type === 'tel') value = '2025550142';
      else if (['number','range'].includes(control.type)) {
        const minimum = control.min === '' ? 1 : Number(control.min);
        const maximum = control.max === '' ? minimum + 10 : Number(control.max);
        value = String(Math.min(maximum, minimum));
      } else if (control.type === 'date') value = fixture.date;
      else if (control.maxLength > 0 && value.length > control.maxLength) {
        if (control === field[0]) return {ok:false};
        value = value.slice(0, control.maxLength);
      }
      if (!setValue(control, value)) return {ok:false};
    }
    return {ok:true, baseline};
  }
  if (phase === 'submit') {
    const buttons = [...form.querySelectorAll('button[type="submit"],button:not([type]),input[type="submit"]')]
      .filter(button => visible(button) && !button.disabled);
    if (buttons.length !== 1) return {ok:false, reason:'SUBMIT_BUTTON_AMBIGUOUS_OR_MISSING'};
    if (!form.checkValidity()) return {ok:false, reason:'FORM_INVALID'};
    buttons[0].click();
    return {ok:true};
  }
  if (phase === 'feedback') {
    const feedback = statuses();
    return {ok:feedback.length > 0 && JSON.stringify(feedback) !== fixture.baseline
      && !root.querySelector('[aria-busy="true"]')};
  }
  if (phase === 'created') return {ok:items(fixture.key).length === 1};
  if (phase === 'updated') return {ok:items(fixture.key).length === 0 && items(fixture.replacement).length === 1};
  if (phase === 'deleted') return {ok:items(fixture.key).length === 0 && items(fixture.replacement).length === 0};
  if (phase === 'edit' || phase === 'delete') {
    const rows = items(phase === 'edit' ? fixture.key : fixture.replacement);
    if (rows.length !== 1) return {ok:false};
    const buttons = [...rows[0].querySelectorAll(`button[data-rendra-action="${phase}"]`)]
      .filter(button => visible(button) && !button.disabled);
    if (buttons.length !== 1) return {ok:false, reason:phase === 'edit' ? 'EDIT_BUTTON_AMBIGUOUS_OR_MISSING' : 'DELETE_BUTTON_AMBIGUOUS_OR_MISSING'};
    buttons[0].click();
    return {ok:true};
  }
  if (phase === 'replace') {
    const fields = [...form.elements].filter(control => visible(control) && !control.disabled && !control.readOnly && control.name === definition.field);
    return {ok:fields.length === 1 && setValue(fields[0], fixture.replacement)};
  }
  return {ok:false};
}

const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

/** Template phases are backend-owned. Random fixture values never enter generation prompts. */
export async function runOutcomes(definitions, page, {budgetMs=12000, stepTimeoutMs=2000, wait=pause, now=Date.now}={}) {
  if (!Array.isArray(definitions) || definitions.length > 24 || !Number.isFinite(budgetMs) || budgetMs < 1
      || !Number.isFinite(stepTimeoutMs) || stepTimeoutMs < 1) throw Error('Invalid outcome capacity');
  const ids = new Set();
  for (const definition of definitions) {
    if (!definition || !/^[A-Za-z][A-Za-z0-9_]{0,47}$/.test(definition.id) || ids.has(definition.id)
        || !['FORM_FEEDBACK','BROWSER_CRUD'].includes(definition.kind)
        || definition.kind === 'BROWSER_CRUD' && !/^[A-Za-z][A-Za-z0-9_]{0,47}$/.test(definition.field)) throw Error('Invalid independent outcome data');
    ids.add(definition.id);
  }
  const deadline = now() + Math.min(25000, budgetMs);
  const results = [];
  for (const definition of definitions) {
    if (now() >= deadline) { results.push({id:definition.id, outcome:'SKIPPED'}); continue; }
    const fixture = {key:'check-' + randomUUID().replaceAll('-','').slice(0,16), replacement:'updated-' + randomUUID().replaceAll('-','').slice(0,16),
      date:new Date(Date.now() + 86400000).toISOString().slice(0,10)};
    let stage='ready', reason;
    const poll = async (phase, label=phase) => {
      stage=label;
      const until = Math.min(deadline, now() + stepTimeoutMs);
      do {
        const reply = await page.evaluate(definition, phase, fixture);
        reason = reply?.reason;
        if (reply?.ok === true) return reply;
        if (now() >= until) break;
        await wait(Math.min(75, until-now()));
      } while (now() < until);
      return null;
    };
    const act = async phase => {
      stage=phase;
      if (now() >= deadline) return null;
      const reply = await page.evaluate(definition, phase, fixture);
      reason = reply?.reason;
      if (reply?.ok !== true) return null;
      await wait(Math.min(150, Math.max(0, deadline-now())));
      return reply;
    };
    const reloaded = async phase => {
      stage='reload';
      return now() < deadline && await page.reload() && !!await poll(phase, 'reload_' + phase);
    };
    let passed = false;
    try {
      const ready = await poll('ready');
      const prepared = ready && await act('prepare');
      if (prepared) fixture.baseline = prepared.baseline;
      const submitted = prepared && await act('submit');
      if (submitted && definition.kind === 'FORM_FEEDBACK') passed = !!await poll('feedback');
      if (submitted && definition.kind === 'BROWSER_CRUD' && await poll('created') && await reloaded('created')
          && await act('edit') && await act('replace') && await act('submit') && await poll('updated')
          && await reloaded('updated') && await act('delete') && await poll('deleted') && await reloaded('deleted')) passed = true;
      const reasons = ['FEATURE_ROOT_MISSING','FEATURE_ROOT_AMBIGUOUS','NATIVE_FORM_MISSING','NATIVE_FORM_AMBIGUOUS',
        'FIELD_MISSING','FIELD_AMBIGUOUS','FIELD_NOT_EDITABLE','SUBMIT_BUTTON_AMBIGUOUS_OR_MISSING','FORM_INVALID',
        'EDIT_BUTTON_AMBIGUOUS_OR_MISSING','DELETE_BUTTON_AMBIGUOUS_OR_MISSING'];
      results.push({id:definition.id, outcome:passed ? 'PASS' : now() >= deadline ? 'SKIPPED' : 'FAIL',
        ...(passed ? {} : {phase:stage, ...(reasons.includes(reason) ? {reason} : {})})});
    } catch {
      results.push({id:definition.id, outcome:'SKIPPED', phase:stage});
    }
  }
  return results;
}
