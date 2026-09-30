import React from 'react';

interface LoadingSpinnerProps {
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

const sizeMap = {
  sm: { width: 60, height: 8 },
  md: { width: 100, height: 12 },
  lg: { width: 140, height: 16 },
};

const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({
  size = 'md',
  className = ''
}) => {
  const dims = sizeMap[size];
  return (
    <div className={className}>
      <div className="shimmer-block" style={{ width: dims.width, height: dims.height }} />
    </div>
  );
};

export default LoadingSpinner;
