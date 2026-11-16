import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ConfirmDialog } from './ConfirmDialog';

const setup = (patch: Partial<React.ComponentProps<typeof ConfirmDialog>> = {}) => {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  render(
    <ConfirmDialog open destructive title="Excluir reunião?" description="Captura de 16/11 11:29 será excluída."
      confirmLabel="Excluir" busyLabel="Excluindo…" onConfirm={onConfirm} onCancel={onCancel} {...patch} />,
  );
  return { onConfirm, onCancel };
};

describe('ConfirmDialog (no lugar de window.confirm)', () => {
  it('mostra título e descrição como alertdialog e abre com o foco em "Cancelar" (ação destrutiva)', () => {
    setup();
    const dialog = screen.getByRole('alertdialog');
    expect(dialog.textContent).toContain('Excluir reunião?');
    expect(dialog.textContent).toContain('Captura de 16/11 11:29');
    expect(document.activeElement?.textContent).toBe('Cancelar');
  });

  it('Excluir confirma; Cancelar, Esc e clique fora cancelam', () => {
    const { onConfirm, onCancel } = setup();
    fireEvent.click(screen.getByText('Excluir'));
    expect(onConfirm).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByText('Cancelar'));
    fireEvent.keyDown(document, { key: 'Escape' });
    fireEvent.mouseDown(screen.getByTestId('confirm-overlay'));
    expect(onCancel).toHaveBeenCalledTimes(3);
  });

  it('durante a exclusão: botões desabilitados, "Excluindo…" e Esc não fecha; erro aparece no diálogo', () => {
    const { onCancel } = setup({ busy: true, error: 'Não foi possível excluir a reunião.' });
    expect(screen.getByText('Excluindo…')).toBeTruthy();
    expect((screen.getByText('Cancelar') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onCancel).not.toHaveBeenCalled();
    expect(screen.getByRole('alert').textContent).toContain('Não foi possível excluir');
  });

  it('fechado não renderiza nada', () => {
    setup({ open: false });
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });
});
