/**
 * ToolMessage 组件
 * 向后兼容入口，内部使用 ToolMessageRouter 路由到专用组件
 */

import React from 'react';
import type { ToolMessageProps } from './tools/types';
import { ToolMessageRouter } from './tools';

/**
 * Tool消息组件（向后兼容）
 * 内部委托给 ToolMessageRouter 根据 toolName 选择专用渲染组件
 */
export const ToolMessage: React.FC<ToolMessageProps> = (props) => {
  return <ToolMessageRouter {...props} />;
};