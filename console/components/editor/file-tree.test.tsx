import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { FileTree } from './file-tree';
import type { AgentFileEntry } from '@/types/homer';

const entries: AgentFileEntry[] = [
  { path: 'agent.yaml', size: 120, mtime: 1, isDirectory: false },
  { path: 'scripts', size: 0, mtime: 2, isDirectory: true },
  { path: 'notes.md', size: 40, mtime: 3, isDirectory: false },
];

describe('FileTree', () => {
  it('renders directories before files', () => {
    render(
      <FileTree entries={entries} activePath={null} onSelect={vi.fn()} />,
    );
    const items = screen.getAllByRole('treeitem');
    expect(items[0].textContent).toContain('scripts');
  });

  it('calls onSelect with the path when a file is clicked', () => {
    const onSelect = vi.fn();
    render(
      <FileTree entries={entries} activePath={null} onSelect={onSelect} />,
    );
    fireEvent.click(screen.getByText('notes.md'));
    expect(onSelect).toHaveBeenCalledWith('notes.md');
  });

  it('does not select a directory click', () => {
    const onSelect = vi.fn();
    render(
      <FileTree entries={entries} activePath={null} onSelect={onSelect} />,
    );
    fireEvent.click(screen.getByText('scripts'));
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('marks the active file and shows aria-selected', () => {
    render(
      <FileTree
        entries={entries}
        activePath="agent.yaml"
        onSelect={vi.fn()}
      />,
    );
    const active = screen.getByText('agent.yaml').closest('[role="treeitem"]');
    expect(active?.getAttribute('aria-selected')).toBe('true');
  });

  it('hides the delete button for protected agent.yaml and agent.json', () => {
    render(
      <FileTree
        entries={entries}
        activePath={null}
        onSelect={vi.fn()}
        onDelete={vi.fn()}
      />,
    );
    expect(screen.queryByLabelText('Delete agent.yaml')).toBeNull();
    expect(screen.queryByLabelText('Delete notes.md')).not.toBeNull();
  });

  it('invokes onNewFile when the + button is clicked', () => {
    const onNewFile = vi.fn();
    render(
      <FileTree
        entries={entries}
        activePath={null}
        onSelect={vi.fn()}
        onNewFile={onNewFile}
      />,
    );
    fireEvent.click(screen.getByLabelText('New file'));
    expect(onNewFile).toHaveBeenCalled();
  });

  it('selects a file from the keyboard with Enter or Space', () => {
    const onSelect = vi.fn();
    render(
      <FileTree entries={entries} activePath={null} onSelect={onSelect} />,
    );
    const item = screen.getByText('notes.md').closest('[role="treeitem"]')!;
    fireEvent.keyDown(item, { key: 'Enter' });
    fireEvent.keyDown(item, { key: ' ' });
    expect(onSelect).toHaveBeenCalledTimes(2);
    expect(onSelect).toHaveBeenCalledWith('notes.md');
  });

  it('ignores other keys and keyboard activation of a directory', () => {
    const onSelect = vi.fn();
    render(
      <FileTree entries={entries} activePath={null} onSelect={onSelect} />,
    );
    const file = screen.getByText('notes.md').closest('[role="treeitem"]')!;
    const dir = screen.getByText('scripts').closest('[role="treeitem"]')!;
    fireEvent.keyDown(file, { key: 'a' });
    fireEvent.keyDown(dir, { key: 'Enter' });
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('deletes a file without selecting it', () => {
    const onSelect = vi.fn();
    const onDelete = vi.fn();
    render(
      <FileTree
        entries={entries}
        activePath={null}
        onSelect={onSelect}
        onDelete={onDelete}
      />,
    );
    fireEvent.click(screen.getByLabelText('Delete notes.md'));
    expect(onDelete).toHaveBeenCalledWith('notes.md');
    expect(onSelect).not.toHaveBeenCalled();
  });
});
